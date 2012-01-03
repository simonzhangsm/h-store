/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB L.L.C.
 *
 * This file contains original code and/or modifications of original code.
 * Any modifications made by VoltDB L.L.C. are licensed under the following
 * terms and conditions:
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */
/* Copyright (C) 2008 by H-Store Project
 * Brown University
 * Massachusetts Institute of Technology
 * Yale University
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

#ifndef VOLTDBENGINE_H
#define VOLTDBENGINE_H

#include <map>
#include <set>
#include <string>
#include <vector>
#include "boost/shared_ptr.hpp"
#include "json_spirit/json_spirit.h"
#include "catalog/database.h"
#include "common/ids.h"
#include "common/serializeio.h"
#include "common/types.h"
#include "common/valuevector.h"
#include "common/Pool.hpp"
#include "common/UndoLog.h"
#include "common/DummyUndoQuantum.hpp"
#include "common/SerializableEEException.h"
#include "common/Topend.h"
#include "common/debuglog.h"
#include "storage/DefaultTupleSerializer.h"
#include "logging/LogManager.h"
#include "logging/LogProxy.h"
#include "logging/StdoutLogProxy.h"
#include "stats/StatsAgent.h"

// shorthand for ExecutionEngine versions generated by javah
#define ENGINE_ERRORCODE_SUCCESS 0
#define ENGINE_ERRORCODE_ERROR 1

#define MAX_BATCH_COUNT 1000
#define MAX_PARAM_COUNT 1000 // or whatever

namespace boost {
template <typename T> class shared_ptr;
}

namespace catalog {
class Catalog;
class PlanFragment;
class Table;
class Statement;
class Cluster;
}

class VoltDBIPC;

namespace voltdb {

class AbstractExecutor;
class AbstractPlanNode;
class SerializeInput;
class SerializeOutput;
class Table;
class ReferenceSerializeInput;
class ReferenceSerializeOutput;
class PlanNodeFragment;
class ExecutorContext;

/**
 * Represents an Execution Engine which holds catalog objects (i.e. table) and executes
 * plans on the objects. Every operation starts from this object.
 * This class is designed to be single-threaded.
 */
// TODO(evanj): Used by JNI so must be exported. Remove when we only one .so
class __attribute__((visibility("default"))) VoltDBEngine {
    public:
        /** Constructor for test code: this does not enable JNI callbacks. */
        VoltDBEngine() :
          m_currentUndoQuantum(NULL),
          m_staticParams(MAX_PARAM_COUNT),
          m_currentOutputDepId(-1),
          m_currentInputDepId(-1),
          m_isELEnabled(false),
          m_numResultDependencies(0),
          m_logManager(new StdoutLogProxy()), m_templateSingleLongTable(NULL), m_topend(NULL)
        {
            m_currentUndoQuantum = new DummyUndoQuantum();
        }

        VoltDBEngine(Topend *topend, LogProxy *logProxy);
        bool initialize(
                int32_t clusterIndex,
                int32_t siteId,
                int32_t partitionId,
                int32_t hostId,
                std::string hostname);
        virtual ~VoltDBEngine();

        inline int32_t getClusterIndex() const { return m_clusterIndex; }
        inline int32_t getSiteId() const { return m_siteId; }

        // ------------------------------------------------------------------
        // OBJECT ACCESS FUNCTIONS
        // ------------------------------------------------------------------
        catalog::Catalog *getCatalog() const;

        Table* getTable(int32_t tableId) const;
        Table* getTable(std::string name) const;
        // Serializes table_id to out. Returns true if successful.
        bool serializeTable(int32_t tableId, int32_t offset, int32_t limit, SerializeOutput* out) const;

        // -------------------------------------------------
        // Execution Functions
        // -------------------------------------------------
        int executeQuery(int64_t planfragmentId, int32_t outputDependencyId, int32_t inputDependencyId,
                         const NValueArray &params, int64_t txnId, int64_t lastCommittedTxnId, bool first, bool last);
        int executePlanFragment(std::string fragmentString, int32_t outputDependencyId, int32_t inputDependencyId,
                                int64_t txnId, int64_t lastCommittedTxnId);

        inline int getUsedParamcnt() const { return m_usedParamcnt;}
        inline void setUsedParamcnt(int usedParamcnt) { m_usedParamcnt = usedParamcnt;}


        // Created to transition existing unit tests to context abstraction.
        // If using this somewhere new, consider if you're being lazy.
        ExecutorContext *getExecutorContext();

        // -------------------------------------------------
        // Dependency Transfer Functions
        // -------------------------------------------------
        bool send(Table* dependency);
        int loadNextDependency(Table* destination);

        // -------------------------------------------------
        // Catalog Functions
        // -------------------------------------------------
        bool loadCatalog(const std::string &catalogPayload);
        bool updateCatalog(const std::string &catalogPayload);
        /**
        * Load table data into a persistent table specified by the tableId parameter.
        * This must be called at most only once before any data is loaded in to the table.
        */
        bool loadTable(bool allowELT, int32_t tableId,
                       ReferenceSerializeInput &serializeIn,
                       int64_t txnId, int64_t lastCommittedTxnId);

        void resetReusedResultOutputBuffer(const size_t headerSize = 0);
        inline ReferenceSerializeOutput* getResultOutputSerializer() { return &m_resultOutput; }
        inline ReferenceSerializeOutput* getExceptionOutputSerializer() { return &m_exceptionOutput; }
        void setBuffers(char *parameter_buffer, int m_parameterBuffercapacity,
                char *resultBuffer, int resultBufferCapacity,
                char *exceptionBuffer, int exceptionBufferCapacity);
        inline const char* getParameterBuffer() const { return m_parameterBuffer;}
        /** Returns the size of buffer for passing parameters to EE. */
        inline int getParameterBufferCapacity() const { return m_parameterBufferCapacity;}

        /**
         * Retrieves the size in bytes of the data that has been placed in the reused result buffer
         */
        int getResultsSize() const;

        /** Returns the buffer for receiving result tables from EE. */
        inline char* getReusedResultBuffer() const { return m_reusedResultBuffer;}
        /** Returns the size of buffer for receiving result tables from EE. */
        inline int getReusedResultBufferCapacity() const { return m_reusedResultCapacity;}

        NValueArray& getParameterContainer() { return m_staticParams; }
        int64_t* getBatchFragmentIdsContainer() { return m_batchFragmentIdsContainer; }
        /** PAVLO **/
        int32_t* getBatchInputDepIdsContainer() { return m_batchInputDepIdsContainer; }
        int32_t* getBatchOutputDepIdsContainer() { return m_batchOutputDepIdsContainer; }
        /** PAVLO **/

        /** are we sending tuples to another database? */
        bool isELEnabled() { return m_isELEnabled; }

        /** check if this value hashes to the local partition */
        bool isLocalSite(int64_t value);
        bool isLocalSite(char *string, int32_t length);


        // -------------------------------------------------
        // Non-transactional work methods
        // -------------------------------------------------

        /** Perform once per second, non-transactional work. */
        void tick(int64_t timeInMillis, int64_t lastCommittedTxnId);

        /** flush active work (like EL buffers) */
        void quiesce(int64_t lastCommittedTxnId);

        // -------------------------------------------------
        // Save and Restore Table to/from disk functions
        // -------------------------------------------------

        /**
         * Save the table specified by catalog id tableId to the
         * absolute path saveFilePath
         *
         * @param tableGuid the GUID of the table in the catalog
         * @param saveFilePath the full path of the desired savefile
         * @return true if successful, false if save failed
         */
        bool saveTableToDisk(int32_t clusterId, int32_t databaseId, int32_t tableId, std::string saveFilePath);

        /**
         * Restore the table from the absolute path saveFilePath
         *
         * @param restoreFilePath the full path of the file with the
         * table to restore
         * @return true if successful, false if save failed
         */
        bool restoreTableFromDisk(std::string restoreFilePath);

        // -------------------------------------------------
        // Debug functions
        // -------------------------------------------------
        std::string debug(void) const;

        /** Counts tuples modified by a plan fragment */
        int64_t m_tuplesModified;
        /** True if any fragments in a batch have modified any tuples */
        bool m_dirtyFragmentBatch;

        std::string m_stmtName;
        std::string m_fragName;

        std::map<std::string, int*> m_indexUsage;

        // -------------------------------------------------
        // Statistics functions
        // -------------------------------------------------
        voltdb::StatsAgent& getStatsManager();

        /**
         * Retrieve a set of statistics and place them into the result buffer as a set of VoltTables.
         * @param selector StatisticsSelectorType indicating what set of statistics should be retrieved
         * @param locators Integer identifiers specifying what subset of possible statistical sources should be polled. Probably a CatalogId
         *                 Can be NULL in which case all possible sources for the selector should be included.
         * @param numLocators Size of locators array.
         * @param interval Whether to return counters since the beginning or since the last time this was called
         * @param Timestamp to embed in each row
         * @return Number of result tables, 0 on no results, -1 on failure.
         */
        int getStats(
                int selector,
                int locators[],
                int numLocators,
                bool interval,
                int64_t now);

        inline Pool* getStringPool() { return &m_stringPool; }

        inline LogManager* getLogManager() {
            return &m_logManager;
        }

        inline void setUndoToken(int64_t nextUndoToken) {
            if (nextUndoToken == INT64_MAX) { return; }
            if (m_currentUndoQuantum != NULL && m_currentUndoQuantum->isDummy()) {
                //std::cout << "Deleting dummy undo quantum " << std::endl;
                delete m_currentUndoQuantum;
                m_currentUndoQuantum = NULL;
            }
            if (m_currentUndoQuantum != NULL) {
                assert(nextUndoToken >= m_currentUndoQuantum->getUndoToken());
                if (m_currentUndoQuantum->getUndoToken() == nextUndoToken) {
                    return;
                }
            }
            m_currentUndoQuantum = m_undoLog.generateUndoQuantum(nextUndoToken);
        }

        inline void releaseUndoToken(int64_t undoToken) {
            if (m_currentUndoQuantum != NULL && m_currentUndoQuantum->isDummy()) {
                return;
            }
            if (m_currentUndoQuantum != NULL && m_currentUndoQuantum->getUndoToken() == undoToken) {
                m_currentUndoQuantum = NULL;
            }
            VOLT_TRACE("Committing Buffer Token %ld at partition %d", undoToken, m_partitionId);
            m_undoLog.release(undoToken);
        }
        inline void undoUndoToken(int64_t undoToken) {
            if (m_currentUndoQuantum != NULL && m_currentUndoQuantum->isDummy()) {
                return;
            }
            VOLT_TRACE("Undoing Buffer Token %ld at partition %d", undoToken, m_partitionId);
            m_undoLog.undo(undoToken);
            m_currentUndoQuantum = NULL;
        }

        inline voltdb::UndoQuantum* getCurrentUndoQuantum() { return m_currentUndoQuantum; }

        inline Topend* getTopend() { return m_topend; }

        /**
         * Get a unique id for a plan fragment by munging the indices of it's parents
         * and grandparents in the catalog.
         */
        static int64_t uniqueIdForFragment(catalog::PlanFragment *frag);

        /**
         * Activate copy on write mode for the specified table.
         * Returns true on success and false on failure
         */
        bool activateCopyOnWrite(const CatalogId tableId);

        /**
         * Serialize more tuples from the specified table that is in COW mode.
         * Returns the number of bytes worth of tuple data serialized or 0 if there are no more.
         * Returns -1 if the table is no in COW mode. The table continues to be in COW (although no copies are made)
         * after all tuples have been serialize until the last call to cowSerializeMore which returns 0 (and deletes
         * the COW context). Further calls will return -1
         */
        int cowSerializeMore(ReferenceSerializeOutput *out, const CatalogId tableId);

        /**
         * Perform an action on behalf of ELT.
         *
         * @param ackAction whether or not this action include a
         * release for stream octets
         * @param pollAction whether or not this action requests the
         * next buffer of unpolled octets
         * @param if ackAction is true, the stream offset being released
         * @param the ID of the table to which this action applies
         * @return the universal offset for any poll results (results
         * returned separatedly via QueryResults buffer)
         */
        long eltAction(bool ackAction, bool pollAction, long ackOffset,
                       int tableId);

    protected:
        /*
         * Get the list of persistent table Ids by inspecting the catalog.
         */
        std::vector<int32_t> getPersistentTableIds();
        std::string getClusterNameFromTable(voltdb::Table *table);
        std::string getDatabaseNameFromTable(voltdb::Table *table);

        // -------------------------------------------------
        // Initialization Functions
        // -------------------------------------------------
        bool clearAndLoadAllPlanFragments();
        bool initTable(const int32_t databaseId, const catalog::Table *catalogTable);
        bool initPlanFragment(const int64_t fragId, const std::string planNodeTree);
        bool initPlanNode(const int64_t fragId, AbstractPlanNode* node, int* tempTableMemoryInBytes);
        bool initCluster(const catalog::Cluster *catalogCluster);
        bool initMaterializedViews();

        void printReport();


        /**
         * Keep a list of executors for runtime - intentionally near the top of VoltDBEngine
         */
        struct ExecutorVector {
            std::vector<AbstractExecutor*> list;
            int tempTableMemoryInBytes;
        };
        std::map<int64_t, boost::shared_ptr<ExecutorVector> > m_executorMap;

        voltdb::UndoLog m_undoLog;
        voltdb::UndoQuantum *m_currentUndoQuantum;

        // -------------------------------------------------
        // Data Members
        // -------------------------------------------------
        int32_t m_siteId;
        int32_t m_partitionId;
        int32_t m_clusterIndex;
        int m_totalPartitions;

        size_t m_startOfResultBuffer;

        /**
         * Tables.
         * We maintain a map of table id's to table objects
         * This contains both intermediate result tables and persistent tables
        */
        std::map<int32_t, Table*> m_tables;
        std::map<std::string, Table*> m_tablesByName;

        /**
         * System Catalog.
        */
        boost::shared_ptr<catalog::Catalog> m_catalog;
        catalog::Database *m_database;

        /** reused parameter container. */
        NValueArray m_staticParams;
        /** TODO : should be passed as execute() parameter..*/
        int m_usedParamcnt;

        /** buffer object for result tables. set when the result table is sent out to localsite. */
        ReferenceSerializeOutput m_resultOutput;

        /** buffer object for exceptions generated by the EE **/
        ReferenceSerializeOutput m_exceptionOutput;

        /** buffer object to pass parameters to EE. */
        const char* m_parameterBuffer;
        /** size of parameter_buffer. */
        int m_parameterBufferCapacity;

        char *m_exceptionBuffer;

        int m_exceptionBufferCapacity;

        /** buffer object to receive result tables from EE. */
        char* m_reusedResultBuffer;
        /** size of reused_result_buffer. */
        int m_reusedResultCapacity;

        int64_t m_batchFragmentIdsContainer[MAX_BATCH_COUNT];
        /** PAVLO **/
        int32_t m_batchInputDepIdsContainer[MAX_BATCH_COUNT];
        int32_t m_batchOutputDepIdsContainer[MAX_BATCH_COUNT];
        /** PAVLO **/

        /** number of plan fragments executed so far */
        int m_pfCount;

        // used for sending and recieving deps
        // set by the executeQuery / executeFrag type methods
        int m_currentOutputDepId;
        int m_currentInputDepId;

        /** EL subsystem on/off, pulled from catalog */
        bool m_isELEnabled;

        /** Stats manager for this execution engine **/
        voltdb::StatsAgent m_statsManager;

        /*
         * Pool for short lived strings that will not live past the return back to Java.
         */
        Pool m_stringPool;

        /*
         * When executing a plan fragment this is set to the number of result dependencies
         * that have been serialized into the m_resultOutput
         */
        int32_t m_numResultDependencies;

        /*
         * Cache plan node fragments in order to allow for deletion.
         */
        std::vector<PlanNodeFragment*> m_planFragments;

        LogManager m_logManager;

        char *m_templateSingleLongTable;

        // depid + table size + status code + header size + column count + column type
        // + column name + tuple count + first row size + modified tuples
        const static int m_templateSingleLongTableSize = 4 + 4 + 1 + 4 + 2 + 1 + 4 + 4 + 4 + 8;

        Topend *m_topend;

        // For data from engine that must be shared/distributed to
        // other components. (Components MUST NOT depend on VoltDBEngine.h).
        ExecutorContext *m_executorContext;

        DefaultTupleSerializer m_tupleSerializer;
};

inline void VoltDBEngine::resetReusedResultOutputBuffer(const size_t headerSize) {
    m_resultOutput.initializeWithPosition(m_reusedResultBuffer, m_reusedResultCapacity, headerSize);
    m_exceptionOutput.initializeWithPosition(m_exceptionBuffer, m_exceptionBufferCapacity, headerSize);
    *reinterpret_cast<int32_t*>(m_exceptionBuffer) = voltdb::VOLT_EE_EXCEPTION_TYPE_NONE;
}

} // namespace voltdb

#endif // VOLTDBENGINE_H
