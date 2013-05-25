package edu.brown.hstore.txns;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;
import org.voltdb.CatalogContext;
import org.voltdb.VoltTable;

import edu.brown.hstore.Hstoreservice.WorkFragment;
import edu.brown.logging.LoggerUtil;
import edu.brown.logging.LoggerUtil.LoggerBoolean;
import edu.brown.pools.Poolable;
import edu.brown.utils.PartitionSet;
import edu.brown.utils.StringUtil;

/**
 * A container for a single output dependency generated by a PlanFragment
 * This can handle results from multiple partitions for the same fragment. 
 * @author pavlo
 */
public class DependencyInfo implements Poolable {
    private static final Logger LOG = Logger.getLogger(DependencyInfo.class);
    private static final LoggerBoolean debug = new LoggerBoolean(LOG.isDebugEnabled());
    private static final LoggerBoolean trace = new LoggerBoolean(LOG.isTraceEnabled());
    static {
        LoggerUtil.attachObserver(LOG, debug, trace);
    }
    
    // ----------------------------------------------------------------------------
    // INVOCATION DATA MEMBERS
    // ----------------------------------------------------------------------------
    
    private Long txn_id;
    private int round;
    private int stmt_counter = -1;
    private int stmt_index = -1;
    private int dependency_id = -1;
    private int params_hash = -1;
    
    /**
     * List of PartitionIds that we expect to get responses/results back
     */
    private final PartitionSet expectedPartitions;
    
    /**
     * The list of VoltTable results that have been sent back from partitions
     * We store it as a list so that we don't have to convert it for ExecutionSite
     */
    private final List<VoltTable> results = new ArrayList<VoltTable>();
    
    /**
     * The List of PartitionIds that we have successfully gotten back from partitions
     */
    private final PartitionSet resultPartitions;
    
    /**
     * We assume a 1-to-n mapping from DependencyInfos to blocked FragmentTaskMessages
     */
    private final Set<WorkFragment.Builder> blockedTasks = new HashSet<WorkFragment.Builder>();
    
    /**
     * If set to true, that means we have already released all the tasks that were 
     * blocked on the results generated for this dependency
     */
    private boolean blockedTasksReleased = false;
    
    /**
     * Is the data for this dependency for intermediate results that
     * are only sent to another WorkFragment (as opposed to being sent back
     * to the transaction's control code). 
     */
    private boolean internal = false;
    
    /**
     * Is the data for this dependency for a prefetched query. If this is
     * set to true, then this 
     */
    private boolean prefetch = false;
    
    // ----------------------------------------------------------------------------
    // INITIALIZATION
    // ----------------------------------------------------------------------------
    
    /**
     * Constructor
     */
    protected DependencyInfo(CatalogContext catalogContext) {
        this.expectedPartitions = new PartitionSet(); // catalogContext.numberOfPartitions);
        this.resultPartitions = new PartitionSet(); // catalogContext.numberOfPartitions);
    }
    
    public void init(Long txn_id, int round, int stmt_counter, int stmt_index, int params_hash, int dependency_id) {
        if (debug.val)
            LOG.debug(String.format("#%s - Initializing DependencyInfo for %s in ROUND #%d",
                      txn_id, TransactionUtil.debugStmtDep(stmt_counter, dependency_id), round));
        this.txn_id = txn_id;
        this.round = round;
        this.stmt_counter = stmt_counter;
        this.stmt_index = stmt_index;
        this.params_hash = params_hash;
        this.dependency_id = dependency_id;
    }
    
    @Override
    public boolean isInitialized() {
        return (this.txn_id != null);
    }
    
    @Override
    public void finish() {
        this.txn_id = null;
        this.stmt_counter = -1;
        this.stmt_index = -1;
        this.dependency_id = -1;
        this.params_hash = -1;
        this.expectedPartitions.clear();
        this.blockedTasks.clear();
        this.blockedTasksReleased = false;
        this.internal = false;
        this.prefetch = false;
        
        this.results.clear();
        this.resultPartitions.clear();
    }
    
    /**
     * Special method for overriding this DependencyInfo's current round 
     * and output dependency id. This is needed for prefetched WorkFragments 
     * that don't have the real id when they were original created.
     * @param round 
     * @param dependency_id
     * @param stmt_index
     */
    protected void prefetchOverride(int round, int dependency_id, int stmt_index) {
        this.round = round;
        this.dependency_id = dependency_id;
        this.stmt_index = stmt_index;
    }
    
    // ----------------------------------------------------------------------------
    // ACCESS METHODS
    // ----------------------------------------------------------------------------

    public Long getTransactionId() {
        return (this.txn_id);
    }
    protected int getRound() {
        return (this.round);
    }
    public int getStatementCounter() {
        return (this.stmt_counter);
    }
    public int getStatementIndex() {
        return (this.stmt_index);
    }
    public int getParameterSetHash() {
        return (this.params_hash);
    }
    public int getDependencyId() {
        return (this.dependency_id);
    }
    
    public boolean inSameTxnRound(Long txn_id, int round) {
        return (txn_id.equals(this.txn_id) && this.round == round);
    }

    public void markInternal() {
        if (debug.val)
            LOG.debug(String.format("#%s - Marking DependencyInfo for %s as internal",
                      this.txn_id, TransactionUtil.debugStmtDep(this.stmt_counter, this.dependency_id)));
        this.internal = true;
    }
    public boolean isInternal() {
        return (this.internal);
    }
    
    public void markPrefetch() {
        this.prefetch = true;
    }
    public void resetPrefetch() {
        this.prefetch = false;
    }
    public boolean isPrefetch() {
        return (this.prefetch);
    }
    
    // ----------------------------------------------------------------------------
    // API METHODS
    // ----------------------------------------------------------------------------
    
    /**
     * Add a FragmentTaskMessage this blocked until all of the partitions return results/responses
     * for this DependencyInfo
     * @param ftask
     */
    public void addBlockedWorkFragment(WorkFragment.Builder ftask) {
        if (trace.val) LOG.trace("Adding block FragmentTaskMessage for txn #" + this.txn_id);
        this.blockedTasks.add(ftask);
    }
    
    /**
     * Return the set of FragmentTaskMessages that are blocked until all of the partitions
     * return results/responses for this DependencyInfo 
     * @return
     */
    protected Collection<WorkFragment.Builder> getBlockedWorkFragments() {
        return (this.blockedTasks);
    }
    
    /**
     * Gets the blocked tasks for this DependencyInfo and marks them as "released"
     * If the tasks have already been released, then the return value will be null;
     * @return
     */
    public Collection<WorkFragment.Builder> getAndReleaseBlockedWorkFragments() {
        if (this.blockedTasksReleased == false) {
            this.blockedTasksReleased = true;
            if (trace.val)
                LOG.trace(String.format("Unblocking %d FragmentTaskMessages for txn #%d",
                          this.blockedTasks.size(), this.txn_id));
            return (this.blockedTasks);
        }
        if (trace.val)
            LOG.trace(String.format("Ignoring duplicate release request for txn #%d", this.txn_id));
        return (null);
    }
    
    /**
     * Add a partition id that we expect to return a result/response for this dependency
     * @param partition
     */
    public void addPartition(int partition) {
        this.expectedPartitions.add(partition);
    }
    /**
     * <B>NOTE:</B> This should only be called for DEBUG purposes only
     */
    protected int getPartitionCount() {
        return (this.expectedPartitions.size());
    }
    /**
     * <B>NOTE:</B> This should only be called for DEBUG purposes only
     */
    protected PartitionSet getExpectedPartitions() {
        return (this.expectedPartitions);
    }
    
    /**
     * Add a result for a PartitionId.
     * Returns true if we have all of the results that we expected to get
     * from all of the partitions.
     * @param partition
     * @param result
     * @return
     */
    public boolean addResult(int partition, VoltTable result) {
        if (debug.val)
            LOG.debug(String.format("#%s - Storing RESULT for DependencyId #%d from partition %02d with %d tuples",
                      this.txn_id, this.dependency_id, partition, result.getRowCount()));
        assert(this.resultPartitions.contains(partition) == false) :
            String.format("Trying to add result %s twice for %s!",
                          TransactionUtil.debugPartDep(partition, this.dependency_id), this.txn_id);
        assert(this.expectedPartitions.contains(partition)) :
            String.format("Unexpected partition result %s for %s!\n%s",
                          TransactionUtil.debugPartDep(partition, this.dependency_id), this.txn_id, this.debug());
        this.results.add(result);
        this.resultPartitions.add(partition);
        return (this.expectedPartitions.size() == this.resultPartitions.size()); 
    }
    
    /**
     * Get the number of results that have arrived so far for this DependencyInfo
     * @return
     */
    protected int getResultsCount() {
        return (this.resultPartitions.size());
    }
    protected List<VoltTable> getResults() {
        return (this.results);
    }
    /**
     * Returns true if this DependencyInfo has all of the results
     * from the partitions that it was expected to get results from.
     * @return
     */
    protected boolean hasAllResults() {
        return (this.expectedPartitions.size() == this.resultPartitions.size());
    }
    
    /**
     * Return just the first result for this DependencyInfo
     * This should only be called to get back the results for the final VoltTable of a query
     * @return
     */
    public VoltTable getResult() {
        assert(this.resultPartitions.size() > 0) : "There are no results available for " + this;
        assert(this.resultPartitions.size() == 1) : 
            "There are " + this.resultPartitions.size() + " results for " + this + "\n-------\n" + this.results;
        return (this.results.get(0));
    }
    
    /**
     * Returns true if the task blocked by this Dependency is now ready to run 
     * @return
     */
    public boolean hasTasksReady() {
        if (debug.val)
            LOG.debug(String.format("txn #%d - hasTasksReady()\n" +
                                    "Block Tasks Not Empty? %s\n" + 
                                    "# of Results:   %d\n" +
                                    "# of Partitions: %d",
                                    this.txn_id,
                                    this.blockedTasks.isEmpty() == false,
                                    this.resultPartitions.size(),
                                    this.expectedPartitions.size()));
        assert(this.resultPartitions.size() <= this.expectedPartitions.size()) :
            String.format("Invalid DependencyInfo state for txn #%d. " +
            		      "There are %d results but %d partitions",
            		      this.txn_id, this.resultPartitions.size(), this.expectedPartitions.size());
        
        return (this.blockedTasks.isEmpty() == false) &&
               (this.blockedTasksReleased == false) &&
               (this.resultPartitions.size() == this.expectedPartitions.size());
    }
    
    public boolean hasTasksBlocked() {
        return (this.blockedTasks.isEmpty() == false);
    }
    
    public boolean hasTasksReleased() {
        return (this.blockedTasksReleased);
    }
    
    // ----------------------------------------------------------------------------
    // DEBUG METHODS
    // ----------------------------------------------------------------------------

    @Override
    public String toString() {
        return String.format("DependencyInfo[#%d/hashCode:%d]",
                             this.dependency_id, this.hashCode());
    }
    
    public String debug() {
        if (this.isInitialized() == false) {
            return ("<UNINITIALIZED>");
        }
        
        String status = null;
        if (this.resultPartitions.size() == this.expectedPartitions.size()) {
            if (this.blockedTasksReleased == false) {
                status = "READY";
            } else {
                status = "RELEASED";
            }
        } else if (this.blockedTasks.isEmpty()) {
            status = "WAITING";
        } else {
            status = "BLOCKED";
        }
        
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("- Internal", this.internal);
        m.put("- Prefetch", this.prefetch);
        m.put("- Round", this.round);
        m.put("- Stmt Counter", this.stmt_counter);
        m.put("- Stmt Index", this.stmt_index);
        m.put("- Parameters Hash", this.params_hash);
        m.put("- Expected Partitions", this.expectedPartitions);
        m.put("- Result Partitions", this.resultPartitions);
        
        Map<String, Object> inner = new LinkedHashMap<String, Object>();
        for (int i = 0; i < this.results.size(); i++) {
            VoltTable vt = this.results.get(i);
            inner.put(String.format("#%02d", i),
                      String.format("{%d tuples}", vt.getRowCount()));  
        } // FOR
        m.put("- Results", inner);
        m.put("- Blocked", this.blockedTasks);
        m.put("- Status", status);

        return (this.toString() + "\n" + StringUtil.formatMaps(m).trim());
    }

}