package com.versionone.git;

import com.versionone.git.configuration.GitConnection;
import com.versionone.git.configuration.ChangeSet;
import com.versionone.git.storage.IDbStorage;

import org.apache.log4j.Logger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.errors.*;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepository;
import org.eclipse.jgit.transport.*;

import java.io.IOException;
import java.net.URISyntaxException;
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

            traverseChanges(builder);

            return builder.build();
        } catch(NotSupportedException ex) {
            LOG.fatal(ex);
            throw new GitException(ex);
        } catch(TransportException ex) {
            LOG.fatal(ex);
            throw new GitException(ex);
        }
    }

    @Override
    public boolean watchedBranchExists() {
        Map<String, Ref> refs = local.getAllRefs();
        String completeBranchName = getWatchedBranchName();

        for(String key : refs.keySet()) {
            if(key.equals(completeBranchName)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public String getWatchedBranchName() {
        return Constants.R_REMOTES + Constants.DEFAULT_REMOTE_NAME +  "/" + gitConnection.getWatchedBranch();
    }

    private void traverseChanges(ChangeSetListBuilder builder) throws GitException {
        Git git = new Git(local);
        LogCommand logCommand = git.log();

    	if(LOG.isDebugEnabled()) {
	        Map<String, Ref> refs = local.getAllRefs();

	        LOG.debug("Available Branches");
	        for (String key : refs.keySet()) {
	            LOG.debug("    " + key + " - " + refs.get(key).getName());
	        }
	        LOG.debug("We are going to process branch " + getWatchedBranchName());
    	}

        Iterable<RevCommit> commits = getCommits(logCommand);

        for (RevCommit commit : commits) {
            // jGit returns data in seconds
            long millisecond = commit.getCommitTime() *  1000l;
            ChangeSetInfo info = new ChangeSetInfo(
                    gitConnection,
                    commit.getAuthorIdent().getName(),
                    commit.getFullMessage().trim(),
                    commit.getId().getName(),
                    new Date(millisecond));

            if(gitConnection.getUseBranchName()) {
                List<String> branches = getBranchNames(commit);
                for(String branch : branches) {
                    fillReferences(branch, info.getReferences());
                }
            } else {
                fillReferences(info.getMessage(), info.getReferences());
            }

            builder.add(info);
        }
    }

    private Iterable<RevCommit> getCommits(LogCommand logCommand) throws GitException {
        Iterable<RevCommit> commits;
        try {
            AnyObjectId headId = local.resolve(Constants.R_REMOTES + "/" + Constants.DEFAULT_REMOTE_NAME +  "/" + gitConnection.getWatchedBranch());
            String headHash = headId.getName();
            String persistedHash = storage.getLastCommit(repositoryId);

            if(persistedHash != null){
                AnyObjectId persistedHeadId = local.resolve(persistedHash);
                LOG.debug("Processing commits from the last head: " + persistedHash);
                //here we get lock for directory
                logCommand.addRange(persistedHeadId, headId);
            } else {
                LOG.debug("Information about last head commit is not found. Processing commits from the beginning.");
                logCommand.add(headId);
            }

            if(!headHash.equals(persistedHash)){
                commits = logCommand.call();
                storage.persistLastCommit(headHash, repositoryId);
            } else {
                LOG.debug("There are no new commits since last run.");
                return new ArrayList<RevCommit>();
            }
        } catch (IOException ex) {
            LOG.fatal(Constants.R_REMOTES + "/" + Constants.DEFAULT_REMOTE_NAME +  "/" + gitConnection.getWatchedBranch() + " can't be processed.", ex);
            throw new GitException(ex);
        } catch (NoHeadException ex) {
            LOG.fatal("Can't find starting revision.", ex);
            throw new GitException(ex);
        } catch (GitAPIException ex) {
            LOG.fatal("An exception occurred in the Git connector while fetching commits:", ex);
        	throw new GitException(ex);
        }
        return commits;
    }

    private void fillReferences(String message, List<String> references) {
        Matcher matcher = Pattern.compile(changeSetConfig.getReferenceExpression()).matcher(message);

        while(matcher.find()) {
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
    	LOG.debug("Cloning repository...");
        local = new FileRepository(localDirectory);
        local.create();

        URIish uri = new URIish(gitConnection.getRepositoryPath());

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
				@Override public void beginTask(String taskName, int totalWork) {LOG.debug(taskName + ", total subtasks: " + totalWork);}
				@Override public void start(int totalTasks) { LOG.debug("Starting task, total tasks: " + totalTasks); }
				@Override public void update(int completed) {}
				@Override public void endTask() {}
				@Override public boolean isCancelled() {return false;}}
        	, null);
		} finally {
			tn.close();
		}
	}
}