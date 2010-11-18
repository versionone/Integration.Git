package com.versionone.git;

public class GitService {

    private final Configuration configuration;

    private final IDbStorage storage;
    private final IGitConnector connector;

    public GitService(Configuration configuration) {
        this.configuration = configuration;
        storage = new DbStorage();
        connector = new GitConnector(configuration.getPassword(), configuration.getPassphrase(), 
                configuration.getRepositoryPath(), configuration.getWatchedBranch(), configuration.getLocalDirectory(),
                configuration.getReferenceExpression(), storage);
    }

    public void onInterval() {
        System.out.println("Hit next interval");
    }
}