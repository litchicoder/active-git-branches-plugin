package io.jenkins.plugins.activegitbranches;

import hudson.model.StringParameterValue;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Parameter value for Active Git Branches parameter.
 * Extends StringParameterValue to store the selected branch name.
 */
public class ActiveGitBranchesParameterValue extends StringParameterValue {

    private static final long serialVersionUID = 1L;

    @DataBoundConstructor
    public ActiveGitBranchesParameterValue(String name, String value) {
        super(name, value);
    }

    public ActiveGitBranchesParameterValue(String name, String value, String description) {
        super(name, value, description);
    }

    @Override
    public String toString() {
        return "(ActiveGitBranchesParameterValue) " + getName() + "='" + getValue() + "'";
    }
}
