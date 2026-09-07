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
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
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

    // ---------------------------------------------------------------------
    // Stale-while-revalidate (SWR) cache. Cache lives for the lifetime of
    // this JVM (cleared on Jenkins restart / plugin reload). Every call to
    // fetchBranches() returns cached data immediately when available and
    // kicks off an async refresh; cold start synchronously fetches once.
    // See discussion in README / chat history for the design rationale.
    // ---------------------------------------------------------------------
    private static final ConcurrentMap<CacheKey, CacheEntry> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<CacheKey, Object> REFRESHING = new ConcurrentHashMap<>();
    private static final Object REFRESH_IN_FLIGHT = new Object();
    private static final ExecutorService REFRESH_EXECUTOR = Executors.newFixedThreadPool(2, new ThreadFactory() {
        private int counter = 0;
        @Override
        public synchronized Thread newThread(Runnable r) {
            Thread t = new Thread(r, "active-git-branches-refresh-" + (++counter));
            t.setDaemon(true);
            return t;
        }
    });

    private final String repositoryUrl;
    private String credentialsId;
    private final int maxBranchCount;
    private String branchFilter;
    private String alwaysIncludeBranches;
    private String excludeBranches;
    private String defaultValue;
    private boolean useQuickFetch = true;
    private boolean allowCustomBranch = false;
    private String subdirectory;

    /**
     * Sentinel option value sent by index.jelly when user picks "Custom..." entry.
     * The actual branch name is then read from the customValue text input.
     */
    static final String CUSTOM_BRANCH_SENTINEL = "__custom__";

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

    public String getExcludeBranches() {
        return excludeBranches;
    }

    @DataBoundSetter
    public void setExcludeBranches(String excludeBranches) {
        this.excludeBranches = excludeBranches;
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

    public boolean isAllowCustomBranch() {
        return allowCustomBranch;
    }

    @DataBoundSetter
    public void setAllowCustomBranch(boolean allowCustomBranch) {
        this.allowCustomBranch = allowCustomBranch;
    }

    public String getSubdirectory() {
        return subdirectory;
    }

    @DataBoundSetter
    public void setSubdirectory(String subdirectory) {
        this.subdirectory = subdirectory;
    }

    @Override
    public ParameterValue createValue(StaplerRequest req, JSONObject jo) {
        String value = jo.optString("value", "");
        if (allowCustomBranch && CUSTOM_BRANCH_SENTINEL.equals(value)) {
            value = jo.optString("customValue", "");
        }
        String sanitized = sanitizeBranchName(value);
        if (matchesExcludeBranches(sanitized)) {
            LOGGER.warning("Rejected branch name because it is excluded: " + sanitized);
            sanitized = "";
        }
        return new ActiveGitBranchesParameterValue(getName(), sanitized, getDescription());
    }

    @Override
    public ParameterValue createValue(StaplerRequest req) {
        String[] values = req.getParameterValues(getName());
        if (values != null && values.length > 0) {
            String value = values[0];
            if (allowCustomBranch && CUSTOM_BRANCH_SENTINEL.equals(value)) {
                String[] customValues = req.getParameterValues("customValue");
                value = (customValues != null && customValues.length > 0) ? customValues[0] : "";
            }
            String sanitized = sanitizeBranchName(value);
            if (matchesExcludeBranches(sanitized)) {
                LOGGER.warning("Rejected branch name because it is excluded: " + sanitized);
                sanitized = "";
            }
            return new ActiveGitBranchesParameterValue(getName(), sanitized, getDescription());
        }
        return getDefaultParameterValue();
    }

    /**
     * Trim whitespace and reject characters that have no business appearing in a git ref name.
     * Returns "" if the input is null/blank or contains forbidden characters; the form layer
     * is expected to surface this back to the user via getDefaultParameterValue() fallback,
     * and the build itself will fail fast if an empty branch is passed downstream.
     */
    static String sanitizeBranchName(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || CUSTOM_BRANCH_SENTINEL.equals(trimmed)) {
            return "";
        }
        // Block obvious injection / malformed ref names (see git-check-ref-format).
        if (trimmed.startsWith("-")
                || trimmed.contains("..")
                || trimmed.contains(" ")
                || trimmed.contains("\t")
                || trimmed.contains("\n")
                || trimmed.contains("\r")
                || trimmed.contains("\\")
                || trimmed.contains("~")
                || trimmed.contains("^")
                || trimmed.contains(":")
                || trimmed.contains("?")
                || trimmed.contains("*")
                || trimmed.contains("[")) {
            LOGGER.warning("Rejected custom branch name with invalid characters: " + trimmed);
            return "";
        }
        return trimmed;
    }

    @Override
    public ParameterValue getDefaultParameterValue() {
        List<BranchInfo> branches = fetchBranches();
        String value = defaultValue;
        if (value != null && matchesExcludeBranches(value)) {
            value = null; // Do not default to a disabled branch
        }
        if ((value == null || value.isEmpty()) && !branches.isEmpty()) {
            for (BranchInfo b : branches) {
                if (!b.isDisabled()) {
                    value = b.getName();
                    break;
                }
            }
            if (value == null) {
                value = branches.get(0).getName();
            }
        }
        return new ActiveGitBranchesParameterValue(getName(), value != null ? value : "", getDescription());
    }

    /**
     * Resolves the effective default branch name to be pre-selected in the UI.
     * Skips any excluded/disabled branch and falls back to the first available active branch.
     */
    public String getEffectiveDefaultValue() {
        ParameterValue pv = getDefaultParameterValue();
        if (pv instanceof ActiveGitBranchesParameterValue) {
            return ((ActiveGitBranchesParameterValue) pv).getValue();
        }
        return defaultValue != null ? defaultValue : "";
    }

    /**
     * Returns the branch list shown to the user. Uses a stale-while-revalidate
     * cache:
     * <ul>
     *   <li><b>Cache hit:</b> return cached snapshot immediately, kick off an
     *       async background refresh so subsequent calls see fresher data.</li>
     *   <li><b>Cache miss (cold start / first call after restart):</b> fetch
     *       synchronously; on success populate the cache, on failure return an
     *       empty list and leave the cache empty so the next call retries.</li>
     * </ul>
     * Background refresh failures keep the existing cached snapshot intact and
     * only flip {@code lastRefreshFailed} so the UI can warn the user.
     */
    public List<BranchInfo> fetchBranches() {
        CacheKey key = buildCacheKey();
        Job<?, ?> currentJob = captureCurrentJob();

        CacheEntry hit = CACHE.get(key);
        if (hit != null) {
            triggerBackgroundRefresh(key, currentJob);
            return hit.branches;
        }

        // Cold start: synchronous fetch; users will wait once.
        try {
            List<BranchInfo> fresh = fetchBranchesInternal(currentJob);
            CACHE.put(key, new CacheEntry(fresh, System.currentTimeMillis(), false));
            return fresh;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to fetch branches from repository: " + repositoryUrl, e);
            return new ArrayList<>();
        }
    }

    private CacheKey buildCacheKey() {
        return new CacheKey(repositoryUrl, credentialsId, maxBranchCount,
                branchFilter, alwaysIncludeBranches, excludeBranches, useQuickFetch, subdirectory);
    }

    /**
     * Human-readable age of the cached branch list (e.g. "5 seconds ago",
     * "2 minutes ago"), or {@code null} if the cache is empty. Exposed for
     * the Jelly view to render a "fetched X ago" hint under the dropdown.
     */
    public String getCacheFetchedAgoText() {
        CacheEntry entry = CACHE.get(buildCacheKey());
        if (entry == null) {
            return null;
        }
        return formatAge(System.currentTimeMillis() - entry.fetchedAt);
    }

    /**
     * Whether the most recent background refresh failed; used by the Jelly
     * view to show a small warning next to the freshness hint.
     */
    public boolean isLastRefreshFailed() {
        CacheEntry entry = CACHE.get(buildCacheKey());
        return entry != null && entry.lastRefreshFailed;
    }

    private static String formatAge(long ageMs) {
        if (ageMs < 1000L) return "just now";
        long seconds = ageMs / 1000L;
        if (seconds < 60) return seconds + (seconds == 1 ? " second ago" : " seconds ago");
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + (minutes == 1 ? " minute ago" : " minutes ago");
        long hours = minutes / 60;
        if (hours < 24) return hours + (hours == 1 ? " hour ago" : " hours ago");
        long days = hours / 24;
        return days + (days == 1 ? " day ago" : " days ago");
    }

    /**
     * Best-effort lookup of the Job ancestor of the current Stapler request,
     * if any. The Job reference (not the request) is what the workspace-based
     * fetch path needs, so we capture it here and pass it down — including to
     * the background refresh thread, where there is no Stapler request anymore.
     */
    private static Job<?, ?> captureCurrentJob() {
        try {
            StaplerRequest req = Stapler.getCurrentRequest();
            if (req == null) return null;
            return req.findAncestorObject(Job.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Kick off an async refresh for the given cache key. Deduplicates: only one
     * refresh per key runs at a time; concurrent callers will all be served
     * from the existing cache entry without piling up git operations.
     */
    private void triggerBackgroundRefresh(CacheKey key, Job<?, ?> job) {
        if (REFRESHING.putIfAbsent(key, REFRESH_IN_FLIGHT) != null) {
            return;
        }
        REFRESH_EXECUTOR.submit(() -> {
            try {
                List<BranchInfo> fresh = fetchBranchesInternal(job);
                CACHE.put(key, new CacheEntry(fresh, System.currentTimeMillis(), false));
                LOGGER.fine("Background branch refresh succeeded for " + repositoryUrl);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING,
                        "Background branch refresh failed for " + repositoryUrl
                                + "; keeping previous cached list", e);
                CacheEntry existing = CACHE.get(key);
                if (existing != null && !existing.lastRefreshFailed) {
                    CACHE.replace(key, existing, existing.withRefreshFailed());
                }
            } finally {
                REFRESHING.remove(key);
            }
        });
    }

    /**
     * Fetches branches from the remote Git repository.
     * Strategy:
     * 1. Try workspace fetch + for-each-ref (lightweight fetch + time-sorted) - PREFERRED
     * 2. If no workspace: use ls-remote (fast, alphabetical) or clone (slow, time-sorted)
     *
     * @param job the current Job, used by the workspace fetch path; may be {@code null}
     *            when running outside a Stapler request (e.g. background refresh) in
     *            which case the workspace path is skipped.
     */
    private List<BranchInfo> fetchBranchesInternal(Job<?, ?> job) throws IOException, InterruptedException {
        if (repositoryUrl == null || repositoryUrl.isEmpty()) {
            throw new IOException("Repository URL is not configured");
        }

        // First, always try workspace-based fetch (lightweight fetch + preserves time sorting)
        List<BranchInfo> result = tryFetchFromWorkspace(job);
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
                    
                    boolean isAlwaysIncluded = matchesAlwaysInclude(branchName);
                    
                    if (!isAlwaysIncluded && !matchesBranchFilter(branchName)) {
                        continue;
                    }
                    
                    boolean isDisabled = matchesExcludeBranches(branchName);
                    // Use 0 as commit time since ls-remote doesn't provide it
                    // Branches will be sorted alphabetically instead
                    branchInfos.add(new BranchInfo(branchName, 0L, isDisabled));
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
     * Returns null if workspace is not available, or when {@code job} is
     * {@code null} (e.g. invoked from a background refresh thread without a
     * Stapler request, in which case we fall through to ls-remote / clone).
     */
    /**
     * Try to fetch branches from existing Job workspace.
     * Uses git fetch + for-each-ref which is faster than cloning.
     * Supports both root workspace and subdirectory repositories (either
     * auto-detected or explicitly configured via {@code subdirectory}).
     * Returns null if workspace is not available, or when {@code job} is
     * {@code null} (e.g. invoked from a background refresh thread without a
     * Stapler request, in which case we fall through to ls-remote / clone).
     */
    private List<BranchInfo> tryFetchFromWorkspace(Job<?, ?> job) {
        if (job == null) {
            LOGGER.fine("No Job context available, skipping workspace fetch");
            return null;
        }
        try {
            // Get workspace
            FilePath workspace = null;
            if (job instanceof AbstractProject) {
                workspace = ((AbstractProject<?, ?>) job).getSomeWorkspace();
            }

            if (workspace == null || !workspace.exists()) {
                LOGGER.fine("Workspace not available for job: " + job.getFullName());
                return null;
            }

            FilePath gitWorkspace = findGitWorkspace(workspace);
            if (gitWorkspace == null) {
                LOGGER.fine("No matching .git repository found in workspace: " + workspace.getRemote());
                return null;
            }

            LOGGER.info("Using workspace for branch fetch: " + gitWorkspace.getRemote());
            return fetchBranchesFromWorkspace(gitWorkspace);

        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Failed to fetch from workspace, will fall back to clone", e);
            return null;
        }
    }

    /**
     * Resolves the Git repository directory inside the workspace.
     * Strategy:
     * 1. If subdirectory is explicitly configured, check workspace/subdirectory
     * 2. Check if the root workspace is the matching git repo
     * 3. Auto-scan direct subdirectories (1 level only) and match remote URL
     * 4. Fallback to root workspace if root has .git (backward compatibility)
     */
    FilePath findGitWorkspace(FilePath workspace) {
        if (workspace == null) {
            return null;
        }

        // 1. Manual subdirectory override
        if (subdirectory != null && !subdirectory.trim().isEmpty()) {
            FilePath custom = workspace.child(subdirectory.trim());
            try {
                if (custom.exists() && custom.child(".git").exists()) {
                    LOGGER.info("Using configured subdirectory for branch fetch: " + custom.getRemote());
                    return custom;
                } else {
                    LOGGER.warning("Configured subdirectory does not exist or has no .git: " + custom.getRemote());
                }
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Error checking configured subdirectory: " + custom.getRemote(), e);
            }
        }

        // 2. Check root workspace
        boolean rootHasGit = false;
        try {
            rootHasGit = workspace.child(".git").exists();
            if (rootHasGit && matchesRemoteUrl(workspace)) {
                LOGGER.info("Using root workspace for branch fetch: " + workspace.getRemote());
                return workspace;
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Error checking root .git", e);
        }

        // 3. Auto-scan direct subdirectories (1 level only)
        try {
            List<FilePath> subDirs = workspace.listDirectories();
            if (subDirs != null) {
                for (FilePath subDir : subDirs) {
                    try {
                        if (subDir.child(".git").exists() && matchesRemoteUrl(subDir)) {
                            LOGGER.info("Auto-detected matching git repository in subdirectory: " + subDir.getName());
                            return subDir;
                        }
                    } catch (Exception e) {
                        LOGGER.log(Level.FINE, "Error checking subdirectory: " + subDir.getRemote(), e);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Failed to scan subdirectories for job workspace: " + workspace.getRemote(), e);
        }

        // 4. Backward compatibility fallback: if root had .git, use root
        if (rootHasGit) {
            LOGGER.info("Using root workspace as fallback: " + workspace.getRemote());
            return workspace;
        }

        return null;
    }

    /**
     * Checks if the directory is a git repository that matches the target repository URL.
     */
    boolean matchesRemoteUrl(FilePath dir) {
        if (repositoryUrl == null || repositoryUrl.trim().isEmpty()) {
            return false;
        }
        try {
            File dirFile = new File(dir.getRemote());
            File gitEntry = new File(dirFile, ".git");
            if (!gitEntry.exists()) {
                return false;
            }
            FileRepositoryBuilder builder = new FileRepositoryBuilder();
            if (gitEntry.isDirectory()) {
                builder.setGitDir(gitEntry);
            } else {
                builder.setWorkTree(dirFile).findGitDir(dirFile);
            }
            try (Repository repo = builder.build()) {
                StoredConfig config = repo.getConfig();
                Set<String> remotes = repo.getRemoteNames();
                for (String remote : remotes) {
                    String[] urls = config.getStringList("remote", remote, "url");
                    if (urls != null) {
                        for (String url : urls) {
                            if (url != null && isSameGitUrl(url, repositoryUrl)) {
                                return true;
                            }
                        }
                    }
                }
                String originUrl = config.getString("remote", "origin", "url");
                if (originUrl != null && isSameGitUrl(originUrl, repositoryUrl)) {
                    return true;
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Failed to inspect git remote in " + dir.getRemote(), e);
        }
        return false;
    }

    /**
     * Compares two Git repository URLs for equivalence, ignoring protocol differences,
     * leading/trailing slashes, and .git extensions.
     */
    static boolean isSameGitUrl(String url1, String url2) {
        if (url1 == null || url2 == null) {
            return false;
        }
        String u1Trimmed = url1.trim();
        String u2Trimmed = url2.trim();
        if (u1Trimmed.equalsIgnoreCase(u2Trimmed)) {
            return true;
        }

        try {
            URIish u1 = new URIish(u1Trimmed);
            URIish u2 = new URIish(u2Trimmed);
            String host1 = u1.getHost();
            String host2 = u2.getHost();
            if (host1 != null && host2 != null && !host1.equalsIgnoreCase(host2)) {
                return false;
            }
            String path1 = normalizeGitPath(u1.getPath());
            String path2 = normalizeGitPath(u2.getPath());
            if (!path1.isEmpty() && path1.equalsIgnoreCase(path2)) {
                return true;
            }
        } catch (Exception e) {
            // Fall back to simple path comparison if URIish parsing fails
        }

        String norm1 = normalizeGitPath(u1Trimmed);
        String norm2 = normalizeGitPath(u2Trimmed);
        return !norm1.isEmpty() && norm1.equalsIgnoreCase(norm2);
    }

    static String normalizeGitPath(String path) {
        if (path == null) {
            return "";
        }
        String p = path.trim().replace('\\', '/');
        int colonIdx = p.indexOf(':');
        if (colonIdx > 0 && !p.startsWith("http://") && !p.startsWith("https://") && !p.startsWith("file://")) {
            p = p.substring(colonIdx + 1);
        }
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.endsWith(".git")) {
            p = p.substring(0, p.length() - 4);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
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
                            
                            boolean isDisabled = matchesExcludeBranches(branchName);
                            try {
                                org.eclipse.jgit.revwalk.RevCommit commit = walk.parseCommit(ref.getObjectId());
                                long commitTime = commit.getCommitTime() * 1000L;
                                branchInfos.add(new BranchInfo(branchName, commitTime, isDisabled));
                            } catch (Exception e) {
                                branchInfos.add(new BranchInfo(branchName, 0L, isDisabled));
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
                                
                                boolean isDisabled = matchesExcludeBranches(branchName);
                                try {
                                    org.eclipse.jgit.revwalk.RevCommit commit = walk.parseCommit(ref.getObjectId());
                                    long commitTime = commit.getCommitTime() * 1000L;
                                    branchInfos.add(new BranchInfo(branchName, commitTime, isDisabled));
                                } catch (Exception e) {
                                    // If we can't parse commit, treat as old
                                    branchInfos.add(new BranchInfo(branchName, 0L, isDisabled));
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
            Pattern pattern = Pattern.compile(alwaysIncludeBranches.trim());
            return pattern.matcher(branchName).matches();
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    public boolean matchesExcludeBranches(String branchName) {
        if (excludeBranches == null || excludeBranches.trim().isEmpty() || branchName == null || branchName.isEmpty()) {
            return false;
        }
        try {
            Pattern pattern = Pattern.compile(excludeBranches.trim());
            return pattern.matcher(branchName).matches();
        } catch (PatternSyntaxException e) {
            LOGGER.warning("Invalid exclude branches regex: " + excludeBranches);
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
        private final boolean disabled;

        public BranchInfo(String name, long commitTime) {
            this(name, commitTime, false);
        }

        public BranchInfo(String name, long commitTime, boolean disabled) {
            this.name = name;
            this.commitTime = commitTime;
            this.disabled = disabled;
        }

        public String getName() {
            return name;
        }

        public long getCommitTime() {
            return commitTime;
        }

        public boolean isDisabled() {
            return disabled;
        }
    }

    /**
     * Identifies a cached branch list. Two parameter definitions whose
     * cache-affecting fields are all equal share the same cache entry, so two
     * Jobs configured against the same repository (with the same credentials,
     * filters, etc.) benefit from each other's warm cache.
     */
    static final class CacheKey {
        private final String repositoryUrl;
        private final String credentialsId;
        private final int maxBranchCount;
        private final String branchFilter;
        private final String alwaysIncludeBranches;
        private final String excludeBranches;
        private final boolean useQuickFetch;
        private final String subdirectory;

        CacheKey(String repositoryUrl, String credentialsId, int maxBranchCount,
                 String branchFilter, String alwaysIncludeBranches, String excludeBranches,
                 boolean useQuickFetch, String subdirectory) {
            this.repositoryUrl = repositoryUrl;
            this.credentialsId = credentialsId;
            this.maxBranchCount = maxBranchCount;
            this.branchFilter = branchFilter;
            this.alwaysIncludeBranches = alwaysIncludeBranches;
            this.excludeBranches = excludeBranches;
            this.useQuickFetch = useQuickFetch;
            this.subdirectory = subdirectory;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CacheKey)) return false;
            CacheKey other = (CacheKey) o;
            return maxBranchCount == other.maxBranchCount
                    && useQuickFetch == other.useQuickFetch
                    && Objects.equals(repositoryUrl, other.repositoryUrl)
                    && Objects.equals(credentialsId, other.credentialsId)
                    && Objects.equals(branchFilter, other.branchFilter)
                    && Objects.equals(alwaysIncludeBranches, other.alwaysIncludeBranches)
                    && Objects.equals(excludeBranches, other.excludeBranches)
                    && Objects.equals(subdirectory, other.subdirectory);
        }

        @Override
        public int hashCode() {
            return Objects.hash(repositoryUrl, credentialsId, maxBranchCount,
                    branchFilter, alwaysIncludeBranches, excludeBranches, useQuickFetch, subdirectory);
        }
    }

    /**
     * Immutable cached snapshot. {@code lastRefreshFailed} signals that the
     * branch list shown to the user may be out of date because the most recent
     * background refresh hit an error; the data itself is kept around so the UI
     * keeps working.
     */
    static final class CacheEntry {
        final List<BranchInfo> branches;
        final long fetchedAt;
        final boolean lastRefreshFailed;

        CacheEntry(List<BranchInfo> branches, long fetchedAt, boolean lastRefreshFailed) {
            this.branches = Collections.unmodifiableList(new ArrayList<>(branches));
            this.fetchedAt = fetchedAt;
            this.lastRefreshFailed = lastRefreshFailed;
        }

        CacheEntry withRefreshFailed() {
            return new CacheEntry(branches, fetchedAt, true);
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
         * Validates the exclude branches regex.
         */
        public FormValidation doCheckExcludeBranches(@QueryParameter String value) {
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
                                                     @QueryParameter String alwaysIncludeBranches,
                                                     @QueryParameter String excludeBranches,
                                                     @QueryParameter String subdirectory) {
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
            tempDef.setExcludeBranches(excludeBranches);
            tempDef.setSubdirectory(subdirectory);

            try {
                List<BranchInfo> branches = tempDef.fetchBranches();
                if (branches.isEmpty()) {
                    items.add("-- No branches found --", "");
                } else {
                    for (BranchInfo branch : branches) {
                        if (branch.isDisabled()) {
                            items.add(branch.getName() + " (disabled)", branch.getName());
                        } else {
                            items.add(branch.getName(), branch.getName());
                        }
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
                                               @QueryParameter String alwaysIncludeBranches,
                                               @QueryParameter String excludeBranches,
                                               @QueryParameter String subdirectory) {
            if (repositoryUrl == null || repositoryUrl.trim().isEmpty()) {
                return FormValidation.error("Repository URL is required");
            }

            ActiveGitBranchesParameterDefinition tempDef = 
                    new ActiveGitBranchesParameterDefinition("temp", repositoryUrl, 
                            maxBranchCount > 0 ? maxBranchCount : 10, null);
            tempDef.setCredentialsId(credentialsId);
            tempDef.setBranchFilter(branchFilter);
            tempDef.setAlwaysIncludeBranches(alwaysIncludeBranches);
            tempDef.setExcludeBranches(excludeBranches);
            tempDef.setSubdirectory(subdirectory);

            try {
                // testConnection is invoked from the config page where no Job context
                // is meaningful for the workspace fetch path, so pass null.
                List<BranchInfo> branches = tempDef.fetchBranchesInternal(null);
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
