package io.jenkins.plugins.activegitbranches;

import hudson.model.ParameterValue;
import hudson.util.FormValidation;
import net.sf.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.kohsuke.stapler.StaplerRequest2;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ActiveGitBranchesParameterDefinition.
 */
public class ActiveGitBranchesParameterDefinitionTest {

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

    @Test
    public void testSubdirectorySetter() {
        ActiveGitBranchesParameterDefinition param = new ActiveGitBranchesParameterDefinition(
                "BRANCH",
                "https://github.com/jenkinsci/jenkins.git",
                10,
                null
        );

        assertNull(param.getSubdirectory());
        param.setSubdirectory("GlazeroAppAndroid");
        assertEquals("GlazeroAppAndroid", param.getSubdirectory());
    }

    @Test
    public void testIsSameGitUrl() {
        // Identical URLs
        assertTrue(ActiveGitBranchesParameterDefinition.isSameGitUrl(
                "https://github.com/org/repo.git", "https://github.com/org/repo.git"));

        // With and without .git
        assertTrue(ActiveGitBranchesParameterDefinition.isSameGitUrl(
                "https://github.com/org/repo.git", "https://github.com/org/repo"));

        // SSH vs HTTPS
        assertTrue(ActiveGitBranchesParameterDefinition.isSameGitUrl(
                "git@github.com:org/repo.git", "https://github.com/org/repo.git"));

        // SSH vs HTTPS without .git
        assertTrue(ActiveGitBranchesParameterDefinition.isSameGitUrl(
                "git@github.com:org/repo.git", "https://github.com/org/repo"));

        // With trailing slash
        assertTrue(ActiveGitBranchesParameterDefinition.isSameGitUrl(
                "https://github.com/org/repo/", "https://github.com/org/repo.git"));

        // Case insensitivity
        assertTrue(ActiveGitBranchesParameterDefinition.isSameGitUrl(
                "https://GitHub.com/Org/Repo.git", "https://github.com/org/repo.git"));

        // Different repos should fail
        assertFalse(ActiveGitBranchesParameterDefinition.isSameGitUrl(
                "https://github.com/org/GlazeroAppAndroid.git", "https://github.com/org/GlazeroAppRN.git"));

        // Different hosts should fail
        assertFalse(ActiveGitBranchesParameterDefinition.isSameGitUrl(
                "https://github.com/org/repo.git", "https://gitlab.com/org/repo.git"));

        // Null checks
        assertFalse(ActiveGitBranchesParameterDefinition.isSameGitUrl(null, "https://github.com/org/repo.git"));
        assertFalse(ActiveGitBranchesParameterDefinition.isSameGitUrl("https://github.com/org/repo.git", null));
    }

    @Test
    public void testFindGitWorkspaceSubdirectory() throws Exception {
        java.io.File tempRoot = java.nio.file.Files.createTempDirectory("jenkins-ws-test-").toFile();
        try {
            // Create GlazeroAppAndroid subdirectory with .git/config
            java.io.File androidDir = new java.io.File(tempRoot, "GlazeroAppAndroid");
            java.io.File androidGit = new java.io.File(androidDir, ".git");
            androidGit.mkdirs();
            java.nio.file.Files.write(new java.io.File(androidGit, "config").toPath(),
                    "[remote \"origin\"]\n\turl = git@github.com:org/GlazeroAppAndroid.git\n".getBytes());

            // Create GlazeroAppRN subdirectory with .git/config
            java.io.File rnDir = new java.io.File(tempRoot, "GlazeroAppRN");
            java.io.File rnGit = new java.io.File(rnDir, ".git");
            rnGit.mkdirs();
            java.nio.file.Files.write(new java.io.File(rnGit, "config").toPath(),
                    "[remote \"origin\"]\n\turl = git@github.com:org/GlazeroAppRN.git\n".getBytes());

            hudson.FilePath ws = new hudson.FilePath(tempRoot);

            // Test 1: Auto-detection for Android repo
            ActiveGitBranchesParameterDefinition paramAndroid = new ActiveGitBranchesParameterDefinition(
                    "BRANCH", "https://github.com/org/GlazeroAppAndroid.git", 10, null);
            hudson.FilePath foundAndroid = paramAndroid.findGitWorkspace(ws);
            assertNotNull(foundAndroid);
            assertEquals("GlazeroAppAndroid", foundAndroid.getName());

            // Test 2: Auto-detection for RN repo
            ActiveGitBranchesParameterDefinition paramRN = new ActiveGitBranchesParameterDefinition(
                    "BRANCH", "https://github.com/org/GlazeroAppRN.git", 10, null);
            hudson.FilePath foundRN = paramRN.findGitWorkspace(ws);
            assertNotNull(foundRN);
            assertEquals("GlazeroAppRN", foundRN.getName());

            // Test 3: Manual subdirectory override
            ActiveGitBranchesParameterDefinition paramManual = new ActiveGitBranchesParameterDefinition(
                    "BRANCH", "https://github.com/org/GlazeroAppRN.git", 10, null);
            paramManual.setSubdirectory("GlazeroAppRN");
            hudson.FilePath foundManual = paramManual.findGitWorkspace(ws);
            assertNotNull(foundManual);
            assertEquals("GlazeroAppRN", foundManual.getName());

            // Test 4: Unknown repo returns null
            ActiveGitBranchesParameterDefinition paramUnknown = new ActiveGitBranchesParameterDefinition(
                    "BRANCH", "https://github.com/org/UnknownRepo.git", 10, null);
            assertNull(paramUnknown.findGitWorkspace(ws));
        } finally {
            deleteRecursively(tempRoot);
        }
    }

    private void deleteRecursively(java.io.File f) {
        if (f.isDirectory()) {
            java.io.File[] files = f.listFiles();
            if (files != null) {
                for (java.io.File child : files) {
                    deleteRecursively(child);
                }
            }
        }
        f.delete();
    }

    @Test
    public void testExcludeBranchesSetter() {
        ActiveGitBranchesParameterDefinition param = new ActiveGitBranchesParameterDefinition(
                "BRANCH",
                "https://github.com/jenkinsci/jenkins.git",
                10,
                null
        );

        assertNull(param.getExcludeBranches());
        param.setExcludeBranches("(master|main)");
        assertEquals("(master|main)", param.getExcludeBranches());
    }

    @Test
    public void testAlwaysIncludeBranchesSetter() {
        ActiveGitBranchesParameterDefinition param = new ActiveGitBranchesParameterDefinition(
                "BRANCH",
                "https://github.com/jenkinsci/jenkins.git",
                10,
                null
        );

        assertNull(param.getAlwaysIncludeBranches());
        param.setAlwaysIncludeBranches("develop|release/.*");
        assertEquals("develop|release/.*", param.getAlwaysIncludeBranches());
    }

    @Test
    public void testValidateExcludeBranches() {
        ActiveGitBranchesParameterDefinition.DescriptorImpl descriptor = 
                new ActiveGitBranchesParameterDefinition.DescriptorImpl();

        // Empty should be OK
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckExcludeBranches("").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckExcludeBranches(null).kind);

        // Valid regex should pass
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckExcludeBranches("(master|main)").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckExcludeBranches("release/.*").kind);

        // Invalid regex should fail
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckExcludeBranches("[invalid").kind);
    }

    @Test
    public void testMatchesExcludeBranches() {
        ActiveGitBranchesParameterDefinition param = new ActiveGitBranchesParameterDefinition(
                "BRANCH",
                "https://github.com/jenkinsci/jenkins.git",
                10,
                null
        );

        // When null or empty, never matches
        assertFalse(param.matchesExcludeBranches("master"));
        assertFalse(param.matchesExcludeBranches(null));

        param.setExcludeBranches("(master|release/.*)");
        assertTrue(param.matchesExcludeBranches("master"));
        assertTrue(param.matchesExcludeBranches("release/1.0.0"));
        assertFalse(param.matchesExcludeBranches("develop"));
        assertFalse(param.matchesExcludeBranches("main"));
        assertFalse(param.matchesExcludeBranches(""));
        assertFalse(param.matchesExcludeBranches(null));

        // Invalid regex should not throw exception, should return false
        param.setExcludeBranches("[invalid");
        assertFalse(param.matchesExcludeBranches("master"));
    }

    @Test
    public void testBranchInfoDisabledProperty() {
        ActiveGitBranchesParameterDefinition.BranchInfo normal = 
                new ActiveGitBranchesParameterDefinition.BranchInfo("develop", 1000L);
        assertFalse(normal.isDisabled());

        ActiveGitBranchesParameterDefinition.BranchInfo disabled = 
                new ActiveGitBranchesParameterDefinition.BranchInfo("master", 2000L, true);
        assertTrue(disabled.isDisabled());
    }

    @Test
    public void testExcludeBranchesSecurityBlockInCreateValue() {
        ActiveGitBranchesParameterDefinition param = new ActiveGitBranchesParameterDefinition(
                "BRANCH",
                "https://github.com/jenkinsci/jenkins.git",
                10,
                null
        );
        param.setExcludeBranches("(master|main)");
        param.setAllowCustomBranch(true);

        // 1. Direct forbidden branch
        JSONObject jo = new JSONObject();
        jo.put("value", "master");
        ParameterValue val = param.createValue((StaplerRequest2) null, jo);
        assertEquals("", ((ActiveGitBranchesParameterValue) val).getValue());

        // 2. Custom input attempting forbidden branch
        JSONObject joCustom = new JSONObject();
        joCustom.put("value", "__custom__");
        joCustom.put("customValue", "main");
        ParameterValue valCustom = param.createValue((StaplerRequest2) null, joCustom);
        assertEquals("", ((ActiveGitBranchesParameterValue) valCustom).getValue());

        // 3. Allowed branch
        JSONObject joAllowed = new JSONObject();
        joAllowed.put("value", "feature/awesome");
        ParameterValue valAllowed = param.createValue((StaplerRequest2) null, joAllowed);
        assertEquals("feature/awesome", ((ActiveGitBranchesParameterValue) valAllowed).getValue());
    }

    @Test
    public void testEffectiveDefaultValue() {
        ActiveGitBranchesParameterDefinition param = new ActiveGitBranchesParameterDefinition(
                "BRANCH",
                "https://github.com/jenkinsci/jenkins.git",
                10,
                null
        );
        param.setDefaultValue("feature/login");
        assertEquals("feature/login", param.getEffectiveDefaultValue());

        // When defaultValue is excluded, it should not return the excluded defaultValue
        param.setExcludeBranches("feature/.*");
        assertNotEquals("feature/login", param.getEffectiveDefaultValue());
    }
}
