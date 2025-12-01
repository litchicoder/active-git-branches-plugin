package io.jenkins.plugins.activegitbranches;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import hudson.EnvVars;
import hudson.util.LogTaskListener;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.jenkinsci.plugins.gitclient.RepositoryCallback;
import hudson.model.TaskListener;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Active Git Branches Parameter Definition.
 * 
 * This parameter plugin dynamically fetches Git branches from a remote repository,
 * sorts them by commit date (descending), and limits the display to the top N branches.
 */
public class ActiveGitBranchesParameterDefinition extends ParameterDefinition {

    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(ActiveGitBranchesParameterDefinition.class.getName());

    private final String repositoryUrl;
    private String credentialsId;
    private final int maxBranchCount;
    private String branchFilter;
    private String alwaysIncludeBranches;
    private String defaultValue;

    @DataBoundConstructor
    public ActiveGitBranchesParameterDefinition(String name, String repositoryUrl, int maxBranchCount, String description) {
        super(name, description);
        this.repositoryUrl = repositoryUrl;
        this.maxBranchCount = maxBranchCount > 0 ? maxBranchCount : 10;
    }

    public String getRepositoryUrl() {
        return repositoryUrl;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = credentialsId;
    }

    public int getMaxBranchCount() {
        return maxBranchCount;
    }

    public String getBranchFilter() {
        return branchFilter;
    }

    @DataBoundSetter
    public void setBranchFilter(String branchFilter) {
        this.branchFilter = branchFilter;
    }

    public String getAlwaysIncludeBranches() {
        return alwaysIncludeBranches;
    }

    @DataBoundSetter
    public void setAlwaysIncludeBranches(String alwaysIncludeBranches) {
        this.alwaysIncludeBranches = alwaysIncludeBranches;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    @DataBoundSetter
    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }

    @Override
    public ParameterValue createValue(StaplerRequest req, JSONObject jo) {
        String value = jo.getString("value");
        return new ActiveGitBranchesParameterValue(getName(), value, getDescription());
    }

    @Override
    public ParameterValue createValue(StaplerRequest req) {
        String[] values = req.getParameterValues(getName());
        if (values != null && values.length > 0) {
            return new ActiveGitBranchesParameterValue(getName(), values[0], getDescription());
        }
        return getDefaultParameterValue();
    }

    @Override
    public ParameterValue getDefaultParameterValue() {
        List<BranchInfo> branches = fetchBranches();
        String value = defaultValue;
        if ((value == null || value.isEmpty()) && !branches.isEmpty()) {
            value = branches.get(0).getName();
        }
        return new ActiveGitBranchesParameterValue(getName(), value != null ? value : "", getDescription());
    }

    /**
     * Fetches branches from the remote Git repository.
     * This method suppresses exceptions and returns an empty list on failure.
     */
    public List<BranchInfo> fetchBranches() {
        try {
            return fetchBranchesInternal();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to fetch branches from repository: " + repositoryUrl, e);
            return new ArrayList<>();
        }
    }

    /**
     * Fetches branches from the remote Git repository.
     * This method throws exceptions on failure.
     */
    private List<BranchInfo> fetchBranchesInternal() throws IOException, InterruptedException {
        final List<BranchInfo> branchInfos = new ArrayList<>();
        
        if (repositoryUrl == null || repositoryUrl.isEmpty()) {
            throw new IOException("Repository URL is not configured");
        }

        StandardCredentials credentials = getCredentials();
        
        // Create a temp directory for the clone
        File tempDir = createTempDirectory();
        
        try {
            TaskListener listener = new LogTaskListener(LOGGER, Level.INFO);
            EnvVars env = new EnvVars();
            
            GitClient git = Git.with(listener, env)
                    .in(tempDir)
                    .using("jgit")
                    .getClient();
            
            if (credentials != null) {
                git.addCredentials(repositoryUrl, credentials);
            }

            // Clone the repository (shallow for performance)
            git.clone_()
                    .url(repositoryUrl)
                    .repositoryName("origin")
                    .shallow(true)
                    .execute();
            
            // Access the repository to iterate branches and get commit dates
            git.withRepository(new RepositoryCallback<Void>() {
                @Override
                public Void invoke(org.eclipse.jgit.lib.Repository repo, hudson.remoting.VirtualChannel channel) throws IOException, InterruptedException {
                    try (org.eclipse.jgit.revwalk.RevWalk walk = new org.eclipse.jgit.revwalk.RevWalk(repo)) {
                        List<org.eclipse.jgit.lib.Ref> allRefs = repo.getRefDatabase().getRefs();
                        for (org.eclipse.jgit.lib.Ref ref : allRefs) {
                            String refName = ref.getName();
                            
                            // Filter for remote branches (refs/remotes/origin/...)
                            if (refName.startsWith("refs/remotes/origin/")) {
                                String branchName = refName.substring("refs/remotes/origin/".length());
                                
                                // Skip HEAD
                                if (branchName.equals("HEAD")) continue;
                                
                                // Apply branch filter
                                // If alwaysIncludeBranches is set and matches, we keep it regardless of branchFilter
                                boolean isAlwaysIncluded = matchesAlwaysInclude(branchName);
                                
                                if (!isAlwaysIncluded && !matchesBranchFilter(branchName)) {
                                    continue;
                                }
                                
                                try {
                                    org.eclipse.jgit.revwalk.RevCommit commit = walk.parseCommit(ref.getObjectId());
                                    long commitTime = commit.getCommitTime() * 1000L;
                                    branchInfos.add(new BranchInfo(branchName, commitTime));
                                } catch (IOException e) {
                                    // If we can't parse commit, treat as old
                                    branchInfos.add(new BranchInfo(branchName, 0L));
                                }
                            }
                        }
                    }
                    return null;
                }
            });

        } finally {
            deleteDirectory(tempDir);
        }

        // Sort by commit date descending
        branchInfos.sort((a, b) -> Long.compare(b.getCommitTime(), a.getCommitTime()));

        // Limit to maxBranchCount, but keep all always-included branches
        if (branchInfos.size() > maxBranchCount) {
            if (alwaysIncludeBranches == null || alwaysIncludeBranches.trim().isEmpty()) {
                return branchInfos.subList(0, maxBranchCount);
            }
            
            // Let's refine the logic:
            // 1. Identify branches that MUST be included.
            // 2. Identify other branches that are candidates.
            // 3. Take all mandatory branches.
            // 4. Fill the remaining slots (up to maxBranchCount) with the best candidates.
            // 5. If mandatory branches already exceed maxBranchCount, we keep them all (and maybe no others).
            
            // Wait, "maxBranchCount" usually implies total display count.
            // If I have 5 important branches and max=10, I show 5 important + 5 recent.
            // If I have 15 important branches and max=10, I show 15 important? Or 10 important?
            // Usually "Always Include" implies "Don't drop this". So I show 15.
            
            List<BranchInfo> mandatory = new ArrayList<>();
            List<BranchInfo> others = new ArrayList<>();
            
            for (BranchInfo info : branchInfos) {
                if (matchesAlwaysInclude(info.getName())) {
                    mandatory.add(info);
                } else {
                    others.add(info);
                }
            }
            
            List<BranchInfo> result = new ArrayList<>(mandatory);
            
            // Fill remaining slots with others
            int slotsLeft = maxBranchCount - mandatory.size();
            if (slotsLeft > 0) {
                for (int i = 0; i < slotsLeft && i < others.size(); i++) {
                    result.add(others.get(i));
                }
            }
            
            // Re-sort by time
            result.sort((a, b) -> Long.compare(b.getCommitTime(), a.getCommitTime()));
            return result;
        }

        return branchInfos;
    }
    
    private boolean matchesAlwaysInclude(String branchName) {
        if (alwaysIncludeBranches == null || alwaysIncludeBranches.trim().isEmpty()) {
            return false;
        }
        try {
            Pattern pattern = Pattern.compile(alwaysIncludeBranches);
            return pattern.matcher(branchName).matches();
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    private StandardCredentials getCredentials() {
        if (credentialsId == null || credentialsId.isEmpty()) {
            return null;
        }
        
        return CredentialsMatchers.firstOrNull(
                com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials(
                        StandardCredentials.class,
                        Jenkins.get(),
                        ACL.SYSTEM,
                        URIRequirementBuilder.fromUri(repositoryUrl).build()
                ),
                CredentialsMatchers.withId(credentialsId)
        );
    }


    private boolean matchesBranchFilter(String branchName) {
        if (branchFilter == null || branchFilter.trim().isEmpty()) {
            return true;
        }
        
        try {
            Pattern pattern = Pattern.compile(branchFilter);
            return pattern.matcher(branchName).matches();
        } catch (PatternSyntaxException e) {
            LOGGER.warning("Invalid branch filter regex: " + branchFilter);
            return true;
        }
    }

    private File createTempDirectory() throws IOException {
        return java.nio.file.Files.createTempDirectory("jenkins-git-branches-").toFile();
    }

    private void deleteDirectory(File directory) {
        if (directory != null && directory.exists()) {
            try {
                java.nio.file.Files.walkFileTree(directory.toPath(), new java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                    @Override
                    public java.nio.file.FileVisitResult visitFile(java.nio.file.Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                        java.nio.file.Files.delete(file);
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }

                    @Override
                    public java.nio.file.FileVisitResult postVisitDirectory(java.nio.file.Path dir, IOException exc) throws IOException {
                        java.nio.file.Files.delete(dir);
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to delete temp directory: " + directory, e);
            }
        }
    }

    /**
     * Branch information holder.
     */
    public static class BranchInfo {
        private final String name;
        private final long commitTime;

        public BranchInfo(String name, long commitTime) {
            this.name = name;
            this.commitTime = commitTime;
        }

        public String getName() {
            return name;
        }

        public long getCommitTime() {
            return commitTime;
        }
    }

    @Symbol("activeGitBranches")
    @Extension
    public static class DescriptorImpl extends ParameterDescriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Active Git Branches Parameter";
        }

        /**
         * Validates the repository URL.
         */
        public FormValidation doCheckRepositoryUrl(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Repository URL is required");
            }
            if (!value.startsWith("http://") && !value.startsWith("https://") && 
                !value.startsWith("git@") && !value.startsWith("ssh://")) {
                return FormValidation.warning("URL should start with http://, https://, git@ or ssh://");
            }
            return FormValidation.ok();
        }

        /**
         * Validates the max branch count.
         */
        public FormValidation doCheckMaxBranchCount(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Max branch count is required");
            }
            try {
                int count = Integer.parseInt(value);
                if (count <= 0) {
                    return FormValidation.error("Max branch count must be greater than 0");
                }
                if (count > 100) {
                    return FormValidation.warning("Large branch counts may cause performance issues");
                }
            } catch (NumberFormatException e) {
                return FormValidation.error("Please enter a valid number");
            }
            return FormValidation.ok();
        }

        /**
         * Validates the branch filter regex.
         */
        public FormValidation doCheckBranchFilter(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.ok();
            }
            try {
                Pattern.compile(value);
            } catch (PatternSyntaxException e) {
                return FormValidation.error("Invalid regex pattern: " + e.getMessage());
            }
            return FormValidation.ok();
        }
        
        /**
         * Validates the always include branches regex.
         */
        public FormValidation doCheckAlwaysIncludeBranches(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.ok();
            }
            try {
                Pattern.compile(value);
            } catch (PatternSyntaxException e) {
                return FormValidation.error("Invalid regex pattern: " + e.getMessage());
            }
            return FormValidation.ok();
        }

        /**
         * Fills the credentials dropdown.
         */
        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item item,
                                                      @QueryParameter String credentialsId,
                                                      @QueryParameter String repositoryUrl) {
            StandardListBoxModel result = new StandardListBoxModel();
            
            if (item == null) {
                if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                    return result.includeCurrentValue(credentialsId);
                }
            } else {
                if (!item.hasPermission(Item.EXTENDED_READ) && 
                    !item.hasPermission(com.cloudbees.plugins.credentials.CredentialsProvider.USE_ITEM)) {
                    return result.includeCurrentValue(credentialsId);
                }
            }

            result.includeEmptyValue();
            result.includeMatchingAs(
                    item instanceof hudson.model.Queue.Task 
                            ? ((hudson.model.Queue.Task) item).getDefaultAuthentication() 
                            : ACL.SYSTEM,
                    item,
                    StandardCredentials.class,
                    URIRequirementBuilder.fromUri(repositoryUrl).build(),
                    CredentialsMatchers.anyOf(
                            CredentialsMatchers.instanceOf(StandardUsernameCredentials.class)
                    )
            );

            return result;
        }

        /**
         * Fills the branch dropdown for the parameter.
         */
        public ListBoxModel doFillDefaultValueItems(@QueryParameter String repositoryUrl,
                                                     @QueryParameter String credentialsId,
                                                     @QueryParameter int maxBranchCount,
                                                     @QueryParameter String branchFilter,
                                                     @QueryParameter String alwaysIncludeBranches) {
            ListBoxModel items = new ListBoxModel();
            
            if (repositoryUrl == null || repositoryUrl.trim().isEmpty()) {
                items.add("-- Configure repository URL first --", "");
                return items;
            }

            // Create a temporary parameter definition to fetch branches
            ActiveGitBranchesParameterDefinition tempDef = 
                    new ActiveGitBranchesParameterDefinition("temp", repositoryUrl, 
                            maxBranchCount > 0 ? maxBranchCount : 10, null);
            tempDef.setCredentialsId(credentialsId);
            tempDef.setBranchFilter(branchFilter);
            tempDef.setAlwaysIncludeBranches(alwaysIncludeBranches);

            try {
                List<BranchInfo> branches = tempDef.fetchBranches();
                if (branches.isEmpty()) {
                    items.add("-- No branches found --", "");
                } else {
                    for (BranchInfo branch : branches) {
                        items.add(branch.getName(), branch.getName());
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to fetch branches", e);
                items.add("-- Error fetching branches --", "");
            }

            return items;
        }

        /**
         * Test connection to the repository.
         */
        public FormValidation doTestConnection(@QueryParameter String repositoryUrl,
                                               @QueryParameter String credentialsId,
                                               @QueryParameter int maxBranchCount,
                                               @QueryParameter String branchFilter,
                                               @QueryParameter String alwaysIncludeBranches) {
            if (repositoryUrl == null || repositoryUrl.trim().isEmpty()) {
                return FormValidation.error("Repository URL is required");
            }

            ActiveGitBranchesParameterDefinition tempDef = 
                    new ActiveGitBranchesParameterDefinition("temp", repositoryUrl, 
                            maxBranchCount > 0 ? maxBranchCount : 10, null);
            tempDef.setCredentialsId(credentialsId);
            tempDef.setBranchFilter(branchFilter);
            tempDef.setAlwaysIncludeBranches(alwaysIncludeBranches);

            try {
                List<BranchInfo> branches = tempDef.fetchBranchesInternal();
                if (branches.isEmpty()) {
                    return FormValidation.warning("Connection successful, but no branches found matching the filter");
                }
                return FormValidation.ok("Success! Found " + branches.size() + " branches. " +
                        "Latest: " + branches.get(0).getName());
            } catch (Exception e) {
                return FormValidation.error("Failed to connect: " + e.getMessage());
            }
        }
    }
}
