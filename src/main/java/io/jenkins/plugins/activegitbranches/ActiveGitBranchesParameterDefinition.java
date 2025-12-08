package io.jenkins.plugins.activegitbranches;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.AbstractProject;
import hudson.model.Item;
import hudson.model.Job;
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
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.Collections;
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
    private boolean useQuickFetch = true;

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

    public boolean isUseQuickFetch() {
        return useQuickFetch;
    }

    @DataBoundSetter
    public void setUseQuickFetch(boolean useQuickFetch) {
        this.useQuickFetch = useQuickFetch;
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
     * Strategy:
     * 1. Try workspace fetch + for-each-ref (lightweight fetch + time-sorted) - PREFERRED
     * 2. If no workspace: use ls-remote (fast, alphabetical) or clone (slow, time-sorted)
     */
    private List<BranchInfo> fetchBranchesInternal() throws IOException, InterruptedException {
        if (repositoryUrl == null || repositoryUrl.isEmpty()) {
            throw new IOException("Repository URL is not configured");
        }

        // First, always try workspace-based fetch (lightweight fetch + preserves time sorting)
        List<BranchInfo> result = tryFetchFromWorkspace();
        if (result != null) {
            LOGGER.info("Fetched branches from workspace with time-based sorting");
            return result;
        }

        // No workspace available, fall back based on useQuickFetch setting
        if (useQuickFetch) {
            // Fast but no time sorting
            LOGGER.info("No workspace available, using ls-remote (alphabetical sort)");
            return fetchBranchesQuick();
        } else {
            // Slow but preserves time sorting
            LOGGER.info("No workspace available, using clone (time-based sort)");
            return fetchBranchesWithFullClone();
        }
    }

    /**
     * Quick fetch using git ls-remote (no clone required).
     * This is much faster but doesn't provide commit timestamps for sorting.
     * Branches are sorted alphabetically instead.
     */
    private List<BranchInfo> fetchBranchesQuick() throws IOException, InterruptedException {
        final List<BranchInfo> branchInfos = new ArrayList<>();
        
        StandardCredentials credentials = getCredentials();
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

            // Use ls-remote to get remote references without cloning
            Map<String, org.eclipse.jgit.lib.ObjectId> remoteRefs = git.getRemoteReferences(
                    repositoryUrl, null, true, false);
            
            for (Map.Entry<String, org.eclipse.jgit.lib.ObjectId> entry : remoteRefs.entrySet()) {
                String refName = entry.getKey();
                
                // Filter for branches (refs/heads/...)
                if (refName.startsWith("refs/heads/")) {
                    String branchName = refName.substring("refs/heads/".length());
                    
                    // Apply branch filter
                    boolean isAlwaysIncluded = matchesAlwaysInclude(branchName);
                    
                    if (!isAlwaysIncluded && !matchesBranchFilter(branchName)) {
                        continue;
                    }
                    
                    // Use 0 as commit time since ls-remote doesn't provide it
                    // Branches will be sorted alphabetically instead
                    branchInfos.add(new BranchInfo(branchName, 0L));
                }
            }
        } finally {
            deleteDirectory(tempDir);
        }

        // Sort alphabetically (since we don't have commit times)
        branchInfos.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        
        return applyLimits(branchInfos);
    }

    /**
     * Try to fetch branches from existing Job workspace.
     * Uses git fetch + for-each-ref which is faster than cloning.
     * Returns null if workspace is not available.
     */
    private List<BranchInfo> tryFetchFromWorkspace() {
        try {
            // Get the current Job from Stapler request context
            StaplerRequest request = Stapler.getCurrentRequest();
            if (request == null) {
                LOGGER.fine("No Stapler request context available");
                return null;
            }
            
            Job<?, ?> job = request.findAncestorObject(Job.class);
            if (job == null) {
                LOGGER.fine("No Job found in request context");
                return null;
            }
            
            // Get workspace
            FilePath workspace = null;
            if (job instanceof AbstractProject) {
                workspace = ((AbstractProject<?, ?>) job).getSomeWorkspace();
            }
            
            if (workspace == null || !workspace.exists()) {
                LOGGER.fine("Workspace not available for job: " + job.getFullName());
                return null;
            }
            
            // Check if .git directory exists
            FilePath gitDir = workspace.child(".git");
            if (!gitDir.exists()) {
                LOGGER.fine("No .git directory in workspace: " + workspace.getRemote());
                return null;
            }
            
            LOGGER.info("Using existing workspace for branch fetch: " + workspace.getRemote());
            return fetchBranchesFromWorkspace(workspace);
            
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Failed to fetch from workspace, will fall back to clone", e);
            return null;
        }
    }

    /**
     * Fetch branches from an existing workspace.
     * Performs a lightweight fetch to update refs, then reads local refs.
     * Returns null if no local refs found (caller should fall back to other methods).
     */
    private List<BranchInfo> fetchBranchesFromWorkspace(FilePath workspace) throws IOException, InterruptedException {
        File workspaceDir = new File(workspace.getRemote());
        
        TaskListener listener = new LogTaskListener(LOGGER, Level.INFO);
        EnvVars env = new EnvVars();
        
        GitClient git = Git.with(listener, env)
                .in(workspaceDir)
                .using("jgit")
                .getClient();
        
        // Add credentials if available
        StandardCredentials credentials = getCredentials();
        if (credentials != null) {
            git.addCredentials(repositoryUrl, credentials);
        }
        
        // Fetch latest refs from remote with RefSpec and prune
        // - RefSpec: get ALL branches including new ones
        // - Prune: remove refs that no longer exist on remote
        try {
            RefSpec refSpec = new RefSpec("+refs/heads/*:refs/remotes/origin/*");
            git.fetch_()
                .from(new URIish(repositoryUrl), Collections.singletonList(refSpec))
                .prune(true)
                .execute();
            LOGGER.info("Fetched latest refs from remote (with RefSpec + prune)");
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to fetch from remote, using cached refs", e);
        }
        
        // Read local refs (now up-to-date)
        List<BranchInfo> localRefs = readLocalRefs(git);
        if (!localRefs.isEmpty()) {
            LOGGER.info("Using local refs (" + localRefs.size() + " branches)");
            return applyLimits(localRefs);
        }
        
        // No local refs found, return null to fall back to other methods
        LOGGER.info("No local refs found in workspace, will use fallback method");
        return null;
    }

    /**
     * Read local refs from the repository without any network operation.
     * Returns empty list if no refs found or repository is invalid.
     */
    private List<BranchInfo> readLocalRefs(GitClient git) {
        final List<BranchInfo> branchInfos = new ArrayList<>();
        
        try {
            git.withRepository(new RepositoryCallback<Void>() {
                @Override
                public Void invoke(org.eclipse.jgit.lib.Repository repo, hudson.remoting.VirtualChannel channel) throws IOException {
                    try (org.eclipse.jgit.revwalk.RevWalk walk = new org.eclipse.jgit.revwalk.RevWalk(repo)) {
                        List<org.eclipse.jgit.lib.Ref> allRefs = repo.getRefDatabase().getRefsByPrefix("refs/remotes/origin/");
                        for (org.eclipse.jgit.lib.Ref ref : allRefs) {
                            String branchName = ref.getName().substring("refs/remotes/origin/".length());
                            
                            if (branchName.equals("HEAD")) continue;
                            
                            boolean isAlwaysIncluded = matchesAlwaysInclude(branchName);
                            if (!isAlwaysIncluded && !matchesBranchFilter(branchName)) {
                                continue;
                            }
                            
                            try {
                                org.eclipse.jgit.revwalk.RevCommit commit = walk.parseCommit(ref.getObjectId());
                                long commitTime = commit.getCommitTime() * 1000L;
                                branchInfos.add(new BranchInfo(branchName, commitTime));
                            } catch (Exception e) {
                                branchInfos.add(new BranchInfo(branchName, 0L));
                            }
                        }
                    }
                    return null;
                }
            });
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Failed to read local refs", e);
            return new ArrayList<>();
        }
        
        // Sort by commit date descending
        branchInfos.sort((a, b) -> Long.compare(b.getCommitTime(), a.getCommitTime()));
        return branchInfos;
    }

    /**
     * Full clone to get commit timestamps (slowest but always works).
     */
    private List<BranchInfo> fetchBranchesWithFullClone() throws IOException, InterruptedException {
        final List<BranchInfo> branchInfos = new ArrayList<>();
        
        StandardCredentials credentials = getCredentials();
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
                                boolean isAlwaysIncluded = matchesAlwaysInclude(branchName);
                                
                                if (!isAlwaysIncluded && !matchesBranchFilter(branchName)) {
                                    continue;
                                }
                                
                                try {
                                    org.eclipse.jgit.revwalk.RevCommit commit = walk.parseCommit(ref.getObjectId());
                                    long commitTime = commit.getCommitTime() * 1000L;
                                    branchInfos.add(new BranchInfo(branchName, commitTime));
                                } catch (Exception e) {
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
        
        return applyLimits(branchInfos);
    }

    /**
     * Apply maxBranchCount limit while respecting alwaysIncludeBranches.
     */
    private List<BranchInfo> applyLimits(List<BranchInfo> branchInfos) {
        if (branchInfos.size() <= maxBranchCount) {
            return branchInfos;
        }

        if (alwaysIncludeBranches == null || alwaysIncludeBranches.trim().isEmpty()) {
            return new ArrayList<>(branchInfos.subList(0, maxBranchCount));
        }

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

        return result;
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
        File tempDir = File.createTempFile("jenkins-git-branches-", "");
        tempDir.delete();
        tempDir.mkdirs();
        return tempDir;
    }

    private void deleteDirectory(File directory) {
        if (directory != null && directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            directory.delete();
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
