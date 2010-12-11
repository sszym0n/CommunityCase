/*
 * Copyright 2000-2009 JetBrains s.r.o.
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
package org.community.intellij.plugins.communitycase.merge;

import com.intellij.ide.util.ElementsChooser;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.VirtualFile;
import org.community.intellij.plugins.communitycase.commands.Command;
import org.community.intellij.plugins.communitycase.commands.LineHandler;
import org.community.intellij.plugins.communitycase.commands.SimpleHandler;
import org.community.intellij.plugins.communitycase.i18n.Bundle;
import org.community.intellij.plugins.communitycase.ui.UiUtil;
import org.community.intellij.plugins.communitycase.Vcs;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Collections;
import java.util.List;
import java.util.StringTokenizer;

/**
 * A dialog for merge action. It represents most options available for git merge.
 */
public class MergeDialog extends DialogWrapper {
  /**
   * The git root available for git merge action
   */
  private JComboBox myGitRoot;
  /**
   * The check box indicating that no commit will be created
   */
  private JCheckBox myNoCommitCheckBox;
  /**
   * The checkbox that suppresses fast forward resolution even if it is available
   */
  private JCheckBox myNoFastForwardCheckBox;
  /**
   * The checkbox that allows squashing all changes from branch into a single commit
   */
  private JCheckBox mySquashCommitCheckBox;
  /**
   * The label containing a name of the current branch
   */
  private JLabel myCurrentBranchText;
  /**
   * The panel containing a chooser of branches to merge
   */
  private JPanel myBranchToMergeContainer;
  /**
   * Chooser of branches to merge
   */
  private ElementsChooser<String> myBranchChooser;
  /**
   * The commit message
   */
  private JTextField myCommitMessage;
  /**
   * The strategy for merge
   */
  private JComboBox myStrategy;
  /**
   * The panel
   */
  private JPanel myPanel;
  /**
   * The log information checkbox
   */
  private JCheckBox myAddLogInformationCheckBox;
  /**
   * The current project
   */
  private final Project myProject;


  /**
   * A constructor
   *
   * @param project     a project to select
   * @param roots       a git repository roots for the project
   * @param defaultRoot a guessed default root
   */
  public MergeDialog(Project project, List<VirtualFile> roots, VirtualFile defaultRoot) {
    super(project, true);
    setTitle(Bundle.getString("merge.branch.title"));
    myProject = project;
    initBranchChooser();
    setOKActionEnabled(false);
    setOKButtonText(Bundle.getString("merge.branch.button"));
    UiUtil.setupRootChooser(myProject, roots, defaultRoot, myGitRoot, myCurrentBranchText);
    UiUtil.imply(mySquashCommitCheckBox, true, myNoCommitCheckBox, true);
    UiUtil.imply(mySquashCommitCheckBox, true, myAddLogInformationCheckBox, false);
    UiUtil.implyDisabled(mySquashCommitCheckBox, true, myCommitMessage);
    UiUtil.exclusive(mySquashCommitCheckBox, true, myNoFastForwardCheckBox, true);
    myGitRoot.addActionListener(new ActionListener() {
      public void actionPerformed(final ActionEvent e) {
        updateBranches();
      }
    });
    updateBranches();
    init();
  }

  /**
   * Initialize {@link #myBranchChooser} component
   */
  private void initBranchChooser() {
    myBranchChooser = new ElementsChooser<String>(true);
    myBranchChooser.setToolTipText(Bundle.getString("merge.branches.tooltip"));
    GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(0, 0, 0, 0);
    c.gridx = 0;
    c.gridy = 0;
    c.weightx = 1;
    c.weighty = 1;
    c.fill = GridBagConstraints.BOTH;
    myBranchToMergeContainer.add(myBranchChooser, c);
    MergeUtil.setupStrategies(myBranchChooser, myStrategy);
    final ElementsChooser.ElementsMarkListener<String> listener = new ElementsChooser.ElementsMarkListener<String>() {
      public void elementMarkChanged(final String element, final boolean isMarked) {
        setOKActionEnabled(myBranchChooser.getMarkedElements().size() != 0);
      }
    };
    listener.elementMarkChanged(null, true);
    myBranchChooser.addElementsMarkListener(listener);
  }


  /**
   * Setup branches for git root, this method should be called when root is changed.
   */
  private void updateBranches() {
    try {
      VirtualFile root = getSelectedRoot();
      SimpleHandler handler = new SimpleHandler(myProject, root, Command.BRANCH);
      handler.setRemote(true);
      handler.setSilent(true);
      handler.addParameters("--no-color", "-a", "--no-merged");
      String output = handler.run();
      myBranchChooser.clear();
      for (StringTokenizer lines = new StringTokenizer(output, "\n", false); lines.hasMoreTokens();) {
        String branch = lines.nextToken().substring(2);
        myBranchChooser.addElement(branch, false);
      }
    }
    catch (VcsException e) {
      Vcs.getInstance(myProject).showErrors(Collections.singletonList(e), Bundle.getString("merge.retrieving.branches"));
    }
  }

  /**
   * @return get line handler configured according to the selected options
   */
  public LineHandler handler() {
    if (!isOK()) {
      throw new IllegalStateException("The handler could be retrieved only if dialog was completed successfully.");
    }
    VirtualFile root = (VirtualFile)myGitRoot.getSelectedItem();
    LineHandler h = new LineHandler(myProject, root, Command.MERGE);
    // ignore merge failure
    h.ignoreErrorCode(1);
    h.setRemote(true);
    if (myNoCommitCheckBox.isSelected()) {
      h.addParameters("--no-commit");
    }
    if (myAddLogInformationCheckBox.isSelected()) {
      h.addParameters("--log");
    }
    final String msg = myCommitMessage.getText().trim();
    if (msg.length() != 0) {
      h.addParameters("-m", msg);
    }
    if (mySquashCommitCheckBox.isSelected()) {
      h.addParameters("--squash");
    }
    if (myNoFastForwardCheckBox.isSelected()) {
      h.addParameters("--no-ff");
    }
    String strategy = (String)myStrategy.getSelectedItem();
    if (!MergeUtil.DEFAULT_STRATEGY.equals(strategy)) {
      h.addParameters("--strategy", strategy);
    }
    for (String branch : myBranchChooser.getMarkedElements()) {
      h.addParameters(branch);
    }
    return h;
  }


  /**
   * {@inheritDoc}
   */
  protected JComponent createCenterPanel() {
    return myPanel;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected String getDimensionServiceKey() {
    return getClass().getName();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected String getHelpId() {
    return "reference.VersionControl.Git.MergeBranches";
  }

  /**
   * @return selected root
   */
  public VirtualFile getSelectedRoot() {
    return (VirtualFile)myGitRoot.getSelectedItem();
  }
}
