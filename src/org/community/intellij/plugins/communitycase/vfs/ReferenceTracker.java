/*
 * Copyright 2000-2010 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.community.intellij.plugins.communitycase.vfs;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.*;
import com.intellij.util.containers.HashSet;
import org.community.intellij.plugins.communitycase.Util;
import org.community.intellij.plugins.communitycase.Vcs;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The tracker for the references
 */
public class ReferenceTracker {
  /**
   * The root references that must be tracked
   */
  private static final Set<String> myRootReferences = new HashSet<String>();

  static {
    myRootReferences.add("HEAD");
    myRootReferences.add("refs");
    myRootReferences.add("MERGE_HEAD");
    // note, they are here intentionally, since their presence changes the way HEAD is interpreted
    myRootReferences.add("rebase_merge");
    myRootReferences.add("rebase_apply");
  }

  /**
   * The context project
   */
  private final Project myProject;
  /**
   * The vcs instance that requested the tracking
   */
  private final Vcs myVcs;
  /**
   * The event multicaster
   */
  private final ReferenceListener myListener;
  /**
   * The listener for vcs roots
   */
  private final RootsListener myRootsListener;
  /**
   * The list of roots
   */
  private final AtomicReference<List<VirtualFile>> myRoots =
    new AtomicReference<List<VirtualFile>>(Collections.<VirtualFile>emptyList());
  /**
   * The list of roots
   */
  private final AtomicReference<State> myState = new AtomicReference<State>(State.DEACTIVATED);
  /**
   * File system listener
   */
  private final MyVfsListener myVfsListener;

  /**
   * The reference tracker
   *
   * @param project  the project
   * @param vcs      the vcs that created tracker
   * @param listener the listener to use for notifications (multicaster is expected)
   */
  public ReferenceTracker(Project project, Vcs vcs, ReferenceListener listener) {
    myProject = project;
    myVcs = vcs;
    myListener = listener;
    myVfsListener = new MyVfsListener();
    myRootsListener = new RootsListener() {
      @Override
      public void RootsChanged() {
        checkRoots();
      }
    };
  }

  /**
   * Start listening for events
   */
  public void activate() {
    if (!myState.compareAndSet(State.DEACTIVATED, State.ACTIVATED)) {
      return;
    }
    if (myProject.isDefault()) {
      return;
    }
    checkRoots();
    LocalFileSystem.getInstance().addVirtualFileListener(myVfsListener);
    myVcs.addRootsListener(myRootsListener);
  }

  /**
   * Deactivate service
   */
  public void deactivate() {
    if (!myState.compareAndSet(State.ACTIVATED, State.DEACTIVATED)) {
      return;
    }
    removeListeners();
  }

  /**
   * Remove listeners from services
   */
  private void removeListeners() {
    myVcs.removeRootsListener(myRootsListener);
    LocalFileSystem.getInstance().removeVirtualFileListener(myVfsListener);
  }

  /**
   * Dispose the tracker removing all listeners
   */
  public void dispose() {
    if (myState.getAndSet(State.DISPOSED) != State.ACTIVATED) {
      return;
    }
    removeListeners();
  }

  /**
   * Visit roots so file listener will be modified if special status changed
   */
  private void checkRoots() {
    try {
      List<VirtualFile> roots = Util.getRoots(myProject, myVcs);
      myRoots.set(roots);
      for (VirtualFile root : roots) {
        final VirtualFile vc = root.findChild(".");
        if (vc != null) {
          for (String name : myRootReferences) {
            final VirtualFile child = vc.findChild(name);
            if (child != null) {
              child.getTimeStamp();
            }
          }
          final VirtualFile infoRefs = root.findFileByRelativePath("info/refs");
          if (infoRefs != null) {
            infoRefs.getTimeStamp();
          }
          visitRecursively(vc.findChild("refs"));
        }
      }
      notifyChanges(null);
    }
    catch (VcsException e) {
      myRoots.set(Collections.<VirtualFile>emptyList());
    }
  }

  private void notifyChanges(VirtualFile root) {
    myListener.referencesChanged(root);
  }

  /**
   * Visit recursively
   *
   * @param child the child directory to visit
   */
  private static void visitRecursively(VirtualFile child) {
    if (child == null) {
      return;
    }
    child.getTimeStamp();
    LinkedList<VirtualFile> toVisit = new LinkedList<VirtualFile>();
    toVisit.add(child);
    while (!toVisit.isEmpty()) {
      VirtualFile file = toVisit.removeLast();
      if (file.isValid() && file.isDirectory()) {
        toVisit.addAll(Arrays.asList(file.getChildren()));
      }
    }
  }

  /**
   * The listener for vfs events
   */
  class MyVfsListener extends VirtualFileAdapter {

    @Override
    public void contentsChanged(VirtualFileEvent event) {
      checkFile(event.getParent(), event.getFileName());
    }

    @Override
    public void fileCreated(VirtualFileEvent event) {
      checkFile(event.getParent(), event.getFileName());
    }

    @Override
    public void fileDeleted(VirtualFileEvent event) {
      checkFile(event.getParent(), event.getFileName());
    }

    @Override
    public void fileMoved(VirtualFileMoveEvent event) {
      checkFile(event.getNewParent(), event.getFileName());
      checkFile(event.getOldParent(), event.getFileName());
    }

    @Override
    public void fileCopied(VirtualFileCopyEvent event) {
      checkFile(event.getParent(), event.getFileName());
    }

    @Override
    public void propertyChanged(VirtualFilePropertyEvent event) {
      if (event.getPropertyName() == VirtualFile.PROP_NAME) {
        checkFile(event.getParent(), (String)event.getOldValue());
        checkFile(event.getParent(), (String)event.getNewValue());
      }
    }

    /**
     * Check if the file change might affect reference mapping
     *
     * @param parent the file parent to check
     * @param name   the name check
     */
    private void checkFile(VirtualFile parent, String name) {
      VirtualFile vc = parent;
      while (vc != null && !vc.getName().equals(".")) {
        vc = vc.getParent();
      }
      if (vc == null) {
        return;
      }
      if (parent == vc && myRootReferences.contains(name)) {
        notifyChanges(vc.getParent());
      }
      else if (parent.getParent() == vc && name.equals("refs") && parent.getName().equals("info")) {
        notifyChanges(vc.getParent());
      }
      else {
        while (parent != null && parent.getParent() != vc) {
          parent = parent.getParent();
        }
        if (parent != null && parent.isDirectory() && parent.getName().equals("refs")) {
          notifyChanges(vc.getParent());
        }
      }
    }
  }

  /**
   * The component state
   */
  private enum State {
    /**
     * Initial state
     */
    DEACTIVATED,
    /**
     * Started state
     */
    ACTIVATED,
    /**
     * Disposed
     */
    DISPOSED
  }
}
