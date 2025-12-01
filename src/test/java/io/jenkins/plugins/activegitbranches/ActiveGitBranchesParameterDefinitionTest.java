package io.jenkins.plugins.activegitbranches;

import hudson.util.FormValidation;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.*;

/**
 * Unit tests for ActiveGitBranchesParameterDefinition.
 */
public class ActiveGitBranchesParameterDefinitionTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    @Test
    public void testParameterCreation() {
        ActiveGitBranchesParameterDefinition param = new ActiveGitBranchesParameterDefinition(
                "BRANCH",
                "https://github.com/jenkinsci/jenkins.git",
                10,
                "Select a branch"
        );

        assertEquals("BRANCH", param.getName());
        assertEquals("https://github.com/jenkinsci/jenkins.git", param.getRepositoryUrl());
        assertEquals(10, param.getMaxBranchCount());
        assertEquals("Select a branch", param.getDescription());
    }

    @Test
    public void testCredentialsIdSetter() {
        ActiveGitBranchesParameterDefinition param = new ActiveGitBranchesParameterDefinition(
                "BRANCH",
                "https://github.com/jenkinsci/jenkins.git",
                10,
                null
        );

        assertNull(param.getCredentialsId());
        param.setCredentialsId("my-credentials");
        assertEquals("my-credentials", param.getCredentialsId());
    }

    @Test
    public void testBranchFilterSetter() {
        ActiveGitBranchesParameterDefinition param = new ActiveGitBranchesParameterDefinition(
                "BRANCH",
                "https://github.com/jenkinsci/jenkins.git",
                10,
                null
        );

        assertNull(param.getBranchFilter());
        param.setBranchFilter("feature/.*");
        assertEquals("feature/.*", param.getBranchFilter());
    }

    @Test
    public void testDefaultValueSetter() {
        ActiveGitBranchesParameterDefinition param = new ActiveGitBranchesParameterDefinition(
                "BRANCH",
                "https://github.com/jenkinsci/jenkins.git",
                10,
                null
        );

        assertNull(param.getDefaultValue());
        param.setDefaultValue("main");
        assertEquals("main", param.getDefaultValue());
    }

    @Test
    public void testMaxBranchCountDefaultsToTenWhenZero() {
        ActiveGitBranchesParameterDefinition param = new ActiveGitBranchesParameterDefinition(
                "BRANCH",
                "https://github.com/jenkinsci/jenkins.git",
                0,
                null
        );

        assertEquals(10, param.getMaxBranchCount());
    }

    @Test
    public void testMaxBranchCountDefaultsToTenWhenNegative() {
        ActiveGitBranchesParameterDefinition param = new ActiveGitBranchesParameterDefinition(
                "BRANCH",
                "https://github.com/jenkinsci/jenkins.git",
                -5,
                null
        );

        assertEquals(10, param.getMaxBranchCount());
    }

    @Test
    public void testDescriptorDisplayName() {
        ActiveGitBranchesParameterDefinition.DescriptorImpl descriptor = 
                new ActiveGitBranchesParameterDefinition.DescriptorImpl();
        
        assertEquals("Active Git Branches Parameter", descriptor.getDisplayName());
    }

    @Test
    public void testValidateRepositoryUrl() {
        ActiveGitBranchesParameterDefinition.DescriptorImpl descriptor = 
                new ActiveGitBranchesParameterDefinition.DescriptorImpl();

        // Empty URL should fail
        FormValidation validation = descriptor.doCheckRepositoryUrl("");
        assertEquals(FormValidation.Kind.ERROR, validation.kind);

        // Null URL should fail
        validation = descriptor.doCheckRepositoryUrl(null);
        assertEquals(FormValidation.Kind.ERROR, validation.kind);

        // Valid HTTPS URL should pass
        validation = descriptor.doCheckRepositoryUrl("https://github.com/user/repo.git");
        assertEquals(FormValidation.Kind.OK, validation.kind);

        // Valid SSH URL should pass
        validation = descriptor.doCheckRepositoryUrl("git@github.com:user/repo.git");
        assertEquals(FormValidation.Kind.OK, validation.kind);

        // Invalid URL should warn
        validation = descriptor.doCheckRepositoryUrl("ftp://invalid.url");
        assertEquals(FormValidation.Kind.WARNING, validation.kind);
    }

    @Test
    public void testValidateMaxBranchCount() {
        ActiveGitBranchesParameterDefinition.DescriptorImpl descriptor = 
                new ActiveGitBranchesParameterDefinition.DescriptorImpl();

        // Empty should fail
        FormValidation validation = descriptor.doCheckMaxBranchCount("");
        assertEquals(FormValidation.Kind.ERROR, validation.kind);

        // Zero should fail
        validation = descriptor.doCheckMaxBranchCount("0");
        assertEquals(FormValidation.Kind.ERROR, validation.kind);

        // Negative should fail
        validation = descriptor.doCheckMaxBranchCount("-1");
        assertEquals(FormValidation.Kind.ERROR, validation.kind);

        // Valid number should pass
        validation = descriptor.doCheckMaxBranchCount("10");
        assertEquals(FormValidation.Kind.OK, validation.kind);

        // Large number should warn
        validation = descriptor.doCheckMaxBranchCount("150");
        assertEquals(FormValidation.Kind.WARNING, validation.kind);

        // Non-numeric should fail
        validation = descriptor.doCheckMaxBranchCount("abc");
        assertEquals(FormValidation.Kind.ERROR, validation.kind);
    }

    @Test
    public void testValidateBranchFilter() {
        ActiveGitBranchesParameterDefinition.DescriptorImpl descriptor = 
                new ActiveGitBranchesParameterDefinition.DescriptorImpl();

        // Empty filter should be OK
        FormValidation validation = descriptor.doCheckBranchFilter("");
        assertEquals(FormValidation.Kind.OK, validation.kind);

        // Null filter should be OK
        validation = descriptor.doCheckBranchFilter(null);
        assertEquals(FormValidation.Kind.OK, validation.kind);

        // Valid regex should be OK
        validation = descriptor.doCheckBranchFilter("feature/.*");
        assertEquals(FormValidation.Kind.OK, validation.kind);

        // Invalid regex should fail
        validation = descriptor.doCheckBranchFilter("[invalid");
        assertEquals(FormValidation.Kind.ERROR, validation.kind);
    }

    @Test
    public void testBranchInfo() {
        ActiveGitBranchesParameterDefinition.BranchInfo info = 
                new ActiveGitBranchesParameterDefinition.BranchInfo("feature/test", 1234567890000L);

        assertEquals("feature/test", info.getName());
        assertEquals(1234567890000L, info.getCommitTime());
    }

    @Test
    public void testParameterValueCreation() {
        ActiveGitBranchesParameterValue value = 
                new ActiveGitBranchesParameterValue("BRANCH", "main");

        assertEquals("BRANCH", value.getName());
        assertEquals("main", value.getValue());
    }

    @Test
    public void testParameterValueWithDescription() {
        ActiveGitBranchesParameterValue value = 
                new ActiveGitBranchesParameterValue("BRANCH", "main", "Main branch");

        assertEquals("BRANCH", value.getName());
        assertEquals("main", value.getValue());
        assertEquals("Main branch", value.getDescription());
    }

    @Test
    public void testParameterValueToString() {
        ActiveGitBranchesParameterValue value = 
                new ActiveGitBranchesParameterValue("BRANCH", "develop");

        String str = value.toString();
        assertTrue(str.contains("ActiveGitBranchesParameterValue"));
        assertTrue(str.contains("BRANCH"));
        assertTrue(str.contains("develop"));
    }
}
