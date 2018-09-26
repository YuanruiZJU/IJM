package runner.git;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import runner.io.ChangeWriter;
import runner.util.DifferFactory;
import runner.util.GitHelper;

import java.io.IOException;
import java.util.Collection;
import java.util.Observable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Created by veit on 22.11.2016.
 */
public class RepositoryAnalyzer extends Observable {
    private static final Logger logger = LogManager.getLogger(RepositoryAnalyzer.class);
    private final int diffTimeout;

    private String repositoryPath;
    private ChangeWriter writer;
    private DifferFactory factory;
    private int totalCommits;
    private int currentCommit;
    private int numThreads;
    private DiffFilter filter = null;

    public RepositoryAnalyzer(
            String repositoryPath, ChangeWriter writer, DifferFactory factory, int numThreads, int diffTimeout) {
        this.repositoryPath = repositoryPath;
        this.writer = writer;
        this.factory = factory;
        this.numThreads = numThreads;
        this.diffTimeout = diffTimeout;
    }

    public void setFilter(DiffFilter filter) {
        this.filter = filter;
    }

    public int getTotalCommits() {
        return this.totalCommits;
    }

    public int getCurrentCommit() {
        return this.currentCommit;
    }

    public void analyzeRepository() {
        try {
            Repository repository = GitHelper.openRepository(this.repositoryPath);
            Collection<RevCommit> commits = GitHelper.getCommits(repository, "HEAD");

            totalCommits = commits.size();
            currentCommit = 0;

            ExecutorService executor = Executors.newFixedThreadPool(numThreads);

            for (RevCommit commit : commits) {
                executor.execute(() -> this.processCommit(repository, commit));
            }

            executor.shutdown();

            try {
                executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                logger.error("Threading error", e);
            }

            writer.close();
            repository.close();

        } catch (IOException e) {
            logger.error("Cannot access repository", e);
        }
    }

    private void processCommit(Repository repository, RevCommit commit) {
        CommitAnalyzer analyzer = new CommitAnalyzer(repository, commit, factory, diffTimeout, writer);
        analyzer.setRevisionFilter(this.filter);
        analyzer.run();
        increaseCurrentCommit();
        System.gc();
    }

    private synchronized void increaseCurrentCommit() {
        this.currentCommit++;
        this.setChanged();
        this.notifyObservers();
    }
}
