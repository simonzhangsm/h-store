package edu.brown.correlations;

import java.io.IOException;

import org.json.*;
import org.voltdb.catalog.*;

import edu.brown.catalog.CatalogUtil;
import edu.brown.catalog.QueryPlanUtil;
import edu.brown.utils.JSONSerializable;
import edu.brown.utils.JSONUtil;

public class Correlation implements Comparable<Correlation>, JSONSerializable {
    enum Members {
        STATEMENT,
        STATEMENT_INDEX,
        STATEMENT_PARAMETER,
        STATEMENT_COLUMN,
        PROCEDURE_PARAMETER,
        PROCEDURE_PARAMETER_INDEX,
        COEFFICIENT,
    }
    
    public Statement statement;
    public Integer statement_index;
    public StmtParameter statement_parameter;
    public Column statement_column;
    public ProcParameter procedure_parameter;
    public Integer procedure_parameter_index;
    public Double coefficient;
    private transient Integer hashcode;

    /**
     * Constructor
     */
    protected Correlation() {
        // Needed for deserialization
    }

    /**
     * Constructor
     * @param catalog_param
     * @param index
     * @param coefficient
     */
    public Correlation(Statement catalog_stmt, Integer catalog_stmt_index, StmtParameter catalog_stmt_param, ProcParameter catalog_proc_param, Integer catalog_proc_param_index, Double coefficient) {
        this.statement = catalog_stmt;
        this.statement_index = catalog_stmt_index.intValue();
        this.statement_parameter = catalog_stmt_param;
        this.procedure_parameter = catalog_proc_param;
        this.procedure_parameter_index = catalog_proc_param_index.intValue();
        this.coefficient = coefficient.doubleValue();

        // My father told me to trust no one...
        // He took that advice to the grave...
        // He ended up shooting a cop that was trying to help when he got a flat tire...
        // Let that be a lesson for you little Billy
        assert(this.procedure_parameter != null);
        assert(this.statement != null);
        assert(this.procedure_parameter.getParent().equals(this.statement.getParent()));
        assert(this.statement_index >= 0);
        assert(this.statement_parameter != null);
        assert(this.statement_parameter.getParent().equals(this.statement));
        assert(this.coefficient != null);
        assert(this.coefficient >= -1.0);
        assert(this.coefficient <= 1.0);
        assert(this.procedure_parameter_index >= 0);
        assert(this.procedure_parameter_index == 0 || (this.procedure_parameter.getIsarray() && this.statement_index >= 0));
        
        // Always grab the Column that the StmtParameter is mapped to in the Statement
        try {
            this.statement_column = QueryPlanUtil.getColumnForStmtParameter(this.statement_parameter);
        } catch (Exception ex) {
            ex.printStackTrace();
            System.exit(1);
        }
    }

    public Statement getStatement() {
        return this.statement;
    }
    
    public Integer getStatementIndex() {
        return this.statement_index;
    }
    
    public StmtParameter getStmtParameter() {
        return this.statement_parameter;
    }
    
    public ProcParameter getProcParameter() {
        return this.procedure_parameter;
    }
    
    public Integer getProcParameterIndex() {
        return this.procedure_parameter_index;
    }
    
    public Double getCoefficient() {
        return this.coefficient;
    }

    public Column getColumn() {
        return this.statement_column;
    }

    
    /**
     * Compares this object to another
     * @return -1 if this object is less than the other, 1 if this object is greater than the other, 0 otherwise 
     */
    @SuppressWarnings("unchecked")
    @Override
    public int compareTo(Correlation o) {
        assert(o != null);
        assert(o instanceof Correlation);
        
        // Sort them by their attributes as they appear in the catalog
        // No! We want to sort them by their coefficients!
        final Comparable items0[] = new Comparable<?>[] {
                this.coefficient,
                this.statement,
                this.statement_index,
                this.statement_parameter,
                this.procedure_parameter,
                this.procedure_parameter_index,
        };
        final Comparable items1[] = new Comparable<?>[] {
                o.coefficient,
                o.statement,
                o.statement_index,
                o.statement_parameter,
                o.procedure_parameter,
                o.procedure_parameter_index,
        };
        assert(items0.length == items1.length);
        for (int i = 0; i < items0.length; i++) {
            int result = items1[i].compareTo(items0[i]);
            if (result != 0) return (result);
        } // FOR
        return (0); 
    }
    
    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Correlation) {
            Correlation c = (Correlation)obj;
            return (this.statement.equals(c.statement) &&
                    this.statement_index.equals(c.statement_index) &&
                    this.statement_parameter.equals(c.statement_parameter) &&
                    this.procedure_parameter.equals(c.procedure_parameter) &&
                    this.procedure_parameter_index.equals(c.procedure_parameter_index));
        }
        return (false);
    }

    @Override
    public int hashCode() {
        if (this.hashcode == null) {
            this.hashcode = (this.statement.getName() +
                              this.statement_index +
                              this.statement_parameter.getName() +
                              this.procedure_parameter.getName() +
                              this.procedure_parameter_index).intern().hashCode();
        }
        return (this.hashcode);
    }

    // ----------------------------------------------------------------------------
    // SERIALIZATION METHODS
    // ----------------------------------------------------------------------------

    @Override
    public void load(String input_path, Database catalog_db) throws IOException {
        JSONUtil.load(this, catalog_db, input_path);
    }
    
    @Override
    public void save(String output_path) throws IOException {
        JSONUtil.save(this, output_path);
    }

    @Override
    public String toJSONString() {
        return (JSONUtil.toJSONString(this));
    }

    @Override
    public void toJSON(JSONStringer stringer) throws JSONException {
        JSONUtil.fieldsToJSON(stringer, this, Correlation.class, Correlation.Members.values());
    }
    
    @Override
    public void fromJSON(JSONObject json_object, Database catalog_db) throws JSONException {
        JSONUtil.fieldsFromJSON(json_object, catalog_db, this, Correlation.class, Correlation.Members.values());
        
        // Hack for the column
        if (this.statement_column == null) {
            try {
                this.statement_column = QueryPlanUtil.getColumnForStmtParameter(this.statement_parameter);
            } catch (Exception ex) {
                ex.printStackTrace();
                System.exit(1);
            }
        }
    }
    
    @Override
    public String toString() {
        return ("{" +
                   "Statement[" + CatalogUtil.getDisplayName(this.statement) + "-#" + String.format("%02d", this.statement_index) + "]," +
                   "StmtParameter[" + this.statement_parameter.getName() + "]," +
                   "ProcParameter[" + this.procedure_parameter.getName() + "-#" + String.format("%02d", this.procedure_parameter_index) + "]," +
                   "Coefficient[" + String.format("%.3g", this.coefficient) + "]" +
               "}");
    }
} // END CLASS
