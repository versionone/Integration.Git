package com.versionone.git;

import com.versionone.git.configuration.GitConnection;
import com.versionone.git.configuration.ChangeSet;
import com.versionone.git.storage.IDbStorage;

import org.apache.log4j.Logger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.errors.*;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepository;
import org.eclipse.jgit.transport.*;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GitConnector implements IGitConnector {
    private FileRepository local;
    private RemoteConfig remoteConfig;

    private final String remoteBranchName = "refs/heads/master";
    private final String remoteName = "origin";

    private final int timeout = 100;
    private GitConnection gitConnection;
    private ChangeSet changeSetConfig;

    private final String localDirectory;
    private final IDbStorage storage;
    private final String repositoryId;

    private static final Logger LOG = Logger.getLogger("GitIntegration");

    public GitConnector(GitConnection gitConnection, String repositoryId, String localDirectory, IDbStorage storage, ChangeSet changeSetConfig) {
        this.gitConnection = gitConnection;
        this.repositoryId = repositoryId;
        this.localDirectory = localDirectory;
        this.storage = storage;
        this.changeSetConfig = changeSetConfig;

        SshSessionFactory.installWithCredentials(gitConnection.getPassword(), gitConnection.getPassphrase());
    }

    public void initRepository() throws GitException {
        LOG.debug("Initalizing repository...");

        try {
            cloneRepository();
            doFetch();
        } catch (IOException ex) {
            LOG.fatal("Local repository creation failed: "+ ex.getMessage());
            throw new GitException(ex);
        } catch (URISyntaxException ex) {
            LOG.fatal("Local repository creation failed: "+ ex.getMessage());
            throw new GitException(ex);
        }
    }

    public List<ChangeSetInfo> getChangeSets() throws GitException {
        try {
            doFetch();

            ChangeSetListBuilder builder = new ChangeSetListBuilder(Pattern.compile(changeSetConfig.getReferenceExpression())) {
                public boolean shouldAdd(ChangeSetInfo changeSet) {
                    if(changeSetConfig.isAlwaysCreate()){
                        return true;
                    }

                    if(gitConnection.getUseBranchName()) {
                        return changeSet.getReferences().size() > 0;
                    } else {
                        return matchByPattern(changeSet.getMessage());
                    }
                }
            };

            Iterable<ChangeSetInfo> changeSets = getChangeSetsFromCommits();

            for (ChangeSetInfo changeSet : changeSets)
                builder.add(changeSet);

            return builder.build();
        } catch(NotSupportedException ex) {
            LOG.fatal(ex);
            throw new GitException(ex);
        } catch(TransportException ex) {
            LOG.fatal(ex);
            throw new GitException(ex);
        }
    }

    private Iterable<ChangeSetInfo> getChangeSetsFromCommits() throws GitException {
        Map<String, ChangeSetInfo> changeSetMap = new HashMap<String, ChangeSetInfo>();

        try {
            Git git = new Git(local);
            Map<String, Ref> refs = new HashMap();

            // Either filter by just the watched branch if one is specified, or get all branch refs while respecting filter setting
            if (gitConnection.getWatchedBranch() != null && !gitConnection.getWatchedBranch().trim().isEmpty()) {
                String branchName = Constants.R_REMOTES + "/" + Constants.DEFAULT_REMOTE_NAME +  "/" + gitConnection.getWatchedBranch();
                refs.put(branchName, local.getRef(branchName));
                LOG.info(String.format("Checking branch '%s'...", branchName));
            }
            else {
                Map<String, Ref> allRefs = local.getAllRefs();

                LOG.debug(String.format("Filtering %s reference%s...", allRefs.size(), refs.size() != 1 ? "s" : ""));

                // Iterate through refs, filtering out tags or ones named in the branch filter
                for (Map.Entry<String, Ref> refEntry : allRefs.entrySet()) {

                    String refKey = refEntry.getKey();

                    // Skip anything other than branches (e.g. tags) since they're not commit objects and
                    // will throw an IncorrectObjectTypeException when setting the log command range
                    if (!refKey.contains("refs/remotes/origin")) {
                        LOG.debug(String.format("Ignoring %s, not a branch", refKey));
                        continue;
                    }

                    // Skip any refs matching the connection's branch filter
                    if (gitConnection.getBranchFilter() != null && !gitConnection.getBranchFilter().trim().isEmpty() && refKey.contains(gitConnection.getBranchFilter())) {
                        LOG.debug(String.format("Ignoring %s, matched the connection's branch filter %s", refKey, gitConnection.getBranchFilter()));
                        continue;
                    }

                    // Add ref to list for processing
                    LOG.debug(String.format("Adding branch %s to list for checking", refKey));
                    refs.put(refKey, refEntry.getValue());
                }
                allRefs.clear();
                LOG.debug(String.format("Checking %s branch%s...", refs.size(), refs.size() != 1 ? "es" : ""));
            }

            int refCounter = 0;

            // Iterate through each branch checking for any new commits since the last one processed
            for (String ref : refs.keySet()) {

                refCounter++;

                try {
                    // For each branch traversal use a new log object, since they're intended to be called only once
                    LogCommand logCommand = git.log();

                    AnyObjectId headId;
                    RevWalk walk = new RevWalk(local);
                    walk.sort(RevSort.COMMIT_TIME_DESC);
                    walk.sort(RevSort.TOPO);

                    headId = local.resolve(refs.get(ref).getName());
                    walk.markStart(walk.parseCommit(headId));

                    String headHash = headId.getName();
                    String persistedHash = storage.getLastCommit(repositoryId, ref);

                    if (persistedHash != null) {
                        AnyObjectId persistedHeadId = local.resolve(persistedHash);
                        LOG.debug(String.format("Checking branch %s (%s of %s) for new commits since the last one processed (%s)...",
                                ref, refCounter, refs.size(), persistedHash));

                        // Here we get lock for directory
                        logCommand.addRange(persistedHeadId, headId);
                    } else {
                        logCommand.add(headId);
                        LOG.debug(String.format("Last commit processed on branch %s (%s of %s) was not found so processing commits from the beginning...",
                                ref, refCounter, refs.size()));
                    }

                    if (!headHash.equals(persistedHash)) {
                        int newCommitCounter = 0;

                        // Search for new commits then immediately convert them into much
                        // less memory-intensive ChangeSetInfo objects containing only the info we need
                        for (RevCommit commit : logCommand.call()) {
                            if (!changeSetMap.containsKey(commit.getId().getName())) {
                                newCommitCounter++;
                                ChangeSetInfo changeSet = getChangeSetFromCommit(commit);
                                changeSetMap.put(changeSet.getRevision(), changeSet);
                            }
                        }
                        if (newCommitCounter > 0) {
                            DecimalFormat df = new DecimalFormat("#,###");
                            LOG.debug(String.format("%s new commit(s) found (%s in total)", df.format(newCommitCounter), df.format(changeSetMap.size())));
                        }
                        storage.persistLastCommit(headHash, repositoryId, ref);
                    } else {
                        LOG.debug("No new commits were found on branch " + ref);
                    }
                } catch (IOException ex) {
                    LOG.error(ref + " couldn't be processed:", ex);
                } catch (NoHeadException ex) {
                    LOG.error("Couldn't find starting revision for " + ref, ex);
                }
            }
        } catch (Exception ex) {
            LOG.fatal("An exception occurred in the Git connector while getting commits:", ex);
            throw new GitException(ex);
        }

        // Convert map to list then immediately clear map
        ArrayList<ChangeSetInfo> changeSetList = new ArrayList<ChangeSetInfo>(changeSetMap.values());
        changeSetMap.clear();

        // Sort commits by commit time which is needed when they've been taken
        // from multiple branches since they won't be listed chronologically
        Comparator comparator = new ChangeSetComparator();
        Collections.sort(changeSetList, comparator);

        return changeSetList;
    }

    private ChangeSetInfo getChangeSetFromCommit(RevCommit commit) {

        // jGit returns data in seconds
        long millisecond = commit.getCommitTime() *  1000l;
        ChangeSetInfo info = new ChangeSetInfo(
                gitConnection,
                commit.getAuthorIdent().getName(),
                commit.getFullMessage().trim(),
                commit.getId().getName(),
                new Date(millisecond));

        if (gitConnection.getUseBranchName()) {
            List<String> branches = getBranchNames(commit);
            for (String branch : branches) {
                fillReferences(branch, info.getReferences());
            }
        } else {
            fillReferences(info.getMessage(), info.getReferences());
        }

        return info;
    }

    private void fillReferences(String message, List<String> references) {
        Matcher matcher = Pattern.compile(changeSetConfig.getReferenceExpression()).matcher(message);

        while(matcher.find()) {
            if (!references.contains(matcher.group()))
                references.add(matcher.group());
        }
    }

    private List<String> getBranchNames(RevCommit commit) {
        List<String> branchNames = new LinkedList<String>();
        Map<String, Ref> refs = local.getAllRefs();

        for (String key : refs.keySet()) {
            AnyObjectId headId;
            RevWalk walk = new RevWalk(local);
            walk.sort(RevSort.COMMIT_TIME_DESC);
            walk.sort(RevSort.TOPO);

            try {
                headId = local.resolve(refs.get(key).getName());
                walk.markStart(walk.parseCommit(headId));
            } catch (IOException e) {
                e.printStackTrace();
            }

            for (RevCommit commitFromBranch : walk) {
                if (commit.equals(commitFromBranch)) {
                    branchNames.add(refs.get(key).getName());
                    break;
                }
            }
        }

        return branchNames;
    }

    private void cloneRepository() throws IOException, URISyntaxException {

        File directory = new File(localDirectory);
        local = new FileRepository(localDirectory);

        URIish uri = new URIish(gitConnection.getRepositoryPath());

        // Unless this repo is configured to always clone on startup,
        // only re-create it if it doesn't already exist.  This avoids re-cloning every
        // restart of the integration, which for larger repos can be unnecessarily time consuming.
        if (gitConnection.getAlwaysCloneOnStartup() || !directory.exists() || !local.getObjectDatabase().exists()) {

            if (directory.exists() && !Utilities.deleteDirectory(directory))
                LOG.warn(localDirectory + " couldn't be deleted, cloning may fail");

            LOG.info("Cloning repository...");
            local.create();
        }
        else {
            LOG.info("Not re-cloning repository as it's already present. If you always want to re-clone, switch the AlwaysCloneOnStartup configuration on for this repo");
        }

		remoteConfig = new RemoteConfig(local.getConfig(), remoteName);
		remoteConfig.addURI(uri);

		final String dst = Constants.R_REMOTES + remoteConfig.getName();
		RefSpec wcrs = new RefSpec();
		wcrs = wcrs.setForceUpdate(true);
		wcrs = wcrs.setSourceDestination(Constants.R_HEADS + "*", dst + "/*"); //$NON-NLS-1$ //$NON-NLS-2$
        remoteConfig.addFetchRefSpec(wcrs);

		local.getConfig().setBoolean("core", null, "bare", true); //$NON-NLS-1$ //$NON-NLS-2$

		remoteConfig.update(local.getConfig());

		String branchName = remoteBranchName;

		// setup the default remote branch for branchName
		local.getConfig().setString("branch", branchName, "remote", remoteName); //$NON-NLS-1$ //$NON-NLS-2$
		local.getConfig().setString("branch", branchName, "merge", remoteBranchName); //$NON-NLS-1$ //$NON-NLS-2$

		local.getConfig().save();

        local.close();
    }

	private void doFetch() throws NotSupportedException, TransportException {
		LOG.debug("Fetching repository...");
		final Transport tn = Transport.open(local, remoteConfig);
		tn.setTimeout(this.timeout);

        try {
        	tn.fetch(new ProgressMonitor() {
				public void beginTask(String taskName, int totalWork) {LOG.debug(taskName + ", total subtasks: " + totalWork);}
				public void start(int totalTasks) { LOG.debug("Starting task, total tasks: " + totalTasks); }
				public void update(int completed) {}
				public void endTask() {}
				public boolean isCancelled() {return false;}}
        	, null);
		} finally {
			tn.close();
		}
	}

    /** Compares two commits and sorts them by commit time in ascending order */
    private class ChangeSetComparator implements Comparator {
        public int compare(Object object1, Object object2) {
            ChangeSetInfo change1 = (ChangeSetInfo)object1;
            ChangeSetInfo change2 = (ChangeSetInfo)object2;

            return change1.getChangeDate().compareTo(change2.getChangeDate());
        }
    }
}