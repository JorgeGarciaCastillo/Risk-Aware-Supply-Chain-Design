package org.scd.model;

import static java.lang.Double.MAX_VALUE;
import static org.scd.Data.DC_OPTIONS;
import static org.scd.Data.FOUR_WEEKS_DELAY_OPTION;
import static org.scd.Data.MAX_DELAY;
import static org.scd.Data.MEAN_DEMAND;
import static org.scd.Data.NO_DELAY_OPTION;
import static org.scd.Data.NUM_BACKUP_POLICIES;
import static org.scd.Data.ONE_WEEK_DELAY_OPTION;
import static org.scd.Data.PLANT_CAPACITY;
import static org.scd.Data.PLANT_OPTIONS;
import static org.scd.Data.SIX_WEEKS_DELAY_OPTION;
import static org.scd.Data.SUPPLIER_CAPACITY;
import static org.scd.Data.SUPPLIER_OPTIONS;
import static org.scd.Data.TWO_WEEK_DELAY_OPTION;
import static org.scd.Designer.RiskMeasure.probFinancialRisk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.scd.Data;
import org.scd.Solution;
import org.scd.Data.BackupOption;
import org.scd.Designer.RiskMeasure;
import org.scd.Solution.BackupPoliciyData;
import org.scd.Solution.BackupPolicy;
import org.scd.sample.RandomScenario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ilog.concert.IloConstraint;
import ilog.concert.IloException;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumExpr;
import ilog.concert.IloNumVar;
import ilog.concert.IloRange;
import ilog.cplex.IloCplex;
import ilog.cplex.IloCplex.UnknownObjectException;

/**
 * This class creates master and subproblems and solves the robust inventory planning problem using Multicut LShaped Method.
 * 
 * The master problem (MIP) selects the backup policy
 * 
 * The subproblem (LP) determines inventory flow for a given policy and a given demand realization
 */
public class MulticutLShaped {

    private static final Logger LOG = LoggerFactory.getLogger(MulticutLShaped.class);

    protected IloCplex master; // the master model
    private BendersCallback bendersCallback;


    private RiskMeasure riskMeasure;
    private List<RandomScenario> scenarios;

    /*
     * MASTER COST
     */

    // Backup Setup Cost
    protected IloLinearNumExpr facBackupCost;

    protected IloNumExpr risk;
    protected IloNumVar[] scenarioRisk;


    // surrogate variable for production cost
    protected IloNumExpr avgProdutionCost;
    protected IloNumVar[] scenarioCost;

    protected Map<RandomScenario, IloNumVar> scenarioToEstCost;

    /*
     * MASTER VARIABLES
     */

    // Backup Policies variables of master 
    protected IloNumVar backupFGPolicy;
    protected IloNumVar backupWIPPolicy;

    protected IloNumVar[] backupSupplierPolicy;
    protected IloNumVar[] backupDCPolicy;
    protected IloNumVar[] backupPlantPolicy;

    protected IloNumVar backupSupplierCapacity;
    protected IloNumVar backupPlantCapacity;
    protected IloNumVar backupDCCapacity;

    protected IloNumVar backupSupplierDelay;
    protected IloNumVar backupPlantDelay;
    protected IloNumVar backupDCDelay;

    protected IloNumVar[] backupSupplierOptionDelay;
    protected IloNumVar[] backupPlantOptionDelay;
    protected IloNumVar[] backupDCOptionDelay;

    protected IloNumVar[] backupSupplierDelayedCapacity;
    protected IloNumVar[] backupPlantDelayedCapacity;
    protected IloNumVar[] backupDCDelayedCapacity;

    protected IloNumVar[] backupSupplierOptionCapacity;
    protected IloNumVar[] backupPlantOptionCapacity;
    protected IloNumVar[] backupDCOptionCapacity;

    // tolerance for comparing subproblem and master problem flow cost values
    private double FUZZ = 1.0e-7;

    private Solution solution;

    /**
     * Escenarios para los que generar la solución
     * 
     * @param scenarios
     * @param riskMeasure
     */
    public MulticutLShaped(List<RandomScenario> sampledScenarios, RiskMeasure riskMeasure) throws IloException {
        solution = new Solution();
        scenarioToEstCost = new LinkedHashMap<>();
        scenarios = sampledScenarios;
        this.riskMeasure = riskMeasure;
        master = new IloCplex();

    }

    /**
     * Builds the master problem model which will decide the policy
     */
    private void buildModel(List<RandomScenario> scenarios) throws IloException {
        buildMasterVariables();
        bendersCallback = new BendersCallback(scenarios);

        buildMasterObjective();
        buildDownsideRiskConstraints();
        buildMasterBackupConstraints();
    }

    /**
     * Configure solver to solve subproblems when a feasible policy is found
     */
    private void configureMasterSolver() throws IloException {
        // attach a Benders callback to the master
        master.use(bendersCallback);

        // Avoid repeating presolve to not loose the solution
        master.setParam(IloCplex.IntParam.RepeatPresolve, 0);
    }

    /**
     * Creates de master problem variables: policy election and policy derived values
     * 
     */
    private void buildMasterVariables() throws IloException {

        // Per scenario cost
        int nbScenarios = scenarios.size();
        scenarioCost = master.numVarArray(nbScenarios, 0.0, MAX_VALUE, buildNames("estProductionCost", nbScenarios));
        scenarioRisk =
                riskMeasure.equals(probFinancialRisk) ? master.boolVarArray(nbScenarios, buildNames("scenarioDownsideRisk", nbScenarios))
                        : master.numVarArray(nbScenarios, 0.0, MAX_VALUE, buildNames("scenarioDownsideRisk", nbScenarios));


        // Strategic inventories
        backupFGPolicy = master.numVar(0.0, Double.MAX_VALUE, "backupFGPolicy");
        backupWIPPolicy = master.numVar(0.0, Double.MAX_VALUE, "backupWIPPolicy");

        // We must add explictly this vars to Cplex as it will not do it when creating the model because at first they don't appear in anny constraint nor in the objective
        master.add(backupFGPolicy);
        master.add(backupWIPPolicy);

        // Binary election of choosing policy i
        backupSupplierPolicy = master.boolVarArray(NUM_BACKUP_POLICIES, buildNames("backupSupplierPolicy", NUM_BACKUP_POLICIES));
        backupPlantPolicy = master.boolVarArray(NUM_BACKUP_POLICIES, buildNames("backupPlantPolicy", NUM_BACKUP_POLICIES));
        backupDCPolicy = master.boolVarArray(NUM_BACKUP_POLICIES, buildNames("backupDCPolicy", NUM_BACKUP_POLICIES));

        // Backup capacity to choose
        backupSupplierCapacity = master.numVar(0.0, Double.MAX_VALUE, "backupSupplierCapacity");
        backupPlantCapacity = master.numVar(0.0, Double.MAX_VALUE, "backupPlant");
        backupDCCapacity = master.numVar(0.0, Double.MAX_VALUE, "backupDC");

        // Delay of the backup
        backupSupplierDelay = master.numVar(0.0, Double.MAX_VALUE, "backupSupplierDelay");
        backupPlantDelay = master.numVar(0.0, Double.MAX_VALUE, "backupPlantDelay");
        backupDCDelay = master.numVar(0.0, Double.MAX_VALUE, "backupDCDelay");

        // Delay per backup option
        backupSupplierOptionDelay = master.numVarArray(NUM_BACKUP_POLICIES, 0.0, Double.MAX_VALUE,
                buildNames("backupSupplierOptionDelay", NUM_BACKUP_POLICIES));
        backupPlantOptionDelay =
                master.numVarArray(NUM_BACKUP_POLICIES, 0.0, Double.MAX_VALUE, buildNames("backupPlantOptionDelay", NUM_BACKUP_POLICIES));
        backupDCOptionDelay =
                master.numVarArray(NUM_BACKUP_POLICIES, 0.0, Double.MAX_VALUE, buildNames("backupDCOptionDelay", NUM_BACKUP_POLICIES));

        // Delayed capacity per backup option
        backupSupplierDelayedCapacity =
                master.numVarArray(MAX_DELAY, 0.0, Double.MAX_VALUE, buildNames("backupSupplierDelayedCapacity", MAX_DELAY));
        backupPlantDelayedCapacity =
                master.numVarArray(MAX_DELAY, 0.0, Double.MAX_VALUE, buildNames("backupPlantDelayedCapacity", MAX_DELAY));
        backupDCDelayedCapacity =
                master.numVarArray(MAX_DELAY, 0.0, Double.MAX_VALUE, buildNames("backupDCDelayedCapacity", MAX_DELAY));

        // Capacity per backup option
        backupSupplierOptionCapacity = master.numVarArray(NUM_BACKUP_POLICIES, 0.0, Double.MAX_VALUE,
                buildNames("backupSupplierOptionCapacity", NUM_BACKUP_POLICIES));
        backupPlantOptionCapacity =
                master.numVarArray(NUM_BACKUP_POLICIES, 0.0, Double.MAX_VALUE, buildNames("backupPlantOptionCapacity", NUM_BACKUP_POLICIES));
        backupDCOptionCapacity =
                master.numVarArray(NUM_BACKUP_POLICIES, 0.0, Double.MAX_VALUE, buildNames("backupDCOptionCapacity", NUM_BACKUP_POLICIES));
    }

    /**
     * Creates the objetive to minize by the master problem: MasterCost = BackupPolicyCost + Average Production Cost of all Scenarios
     * 
     * @throws IloException
     */
    private void buildMasterObjective() throws IloException {
        // BackUp Policy Cost
        facBackupCost = master.linearNumExpr();
        for (int i = 0; i < Data.NUM_BACKUP_POLICIES; i++) {
            facBackupCost.addTerm(backupSupplierPolicy[i], SUPPLIER_OPTIONS[i].getCost());
            facBackupCost.addTerm(backupPlantPolicy[i], PLANT_OPTIONS[i].getCost());
            facBackupCost.addTerm(backupDCPolicy[i], DC_OPTIONS[i].getCost());
        }
        facBackupCost.addTerm(backupWIPPolicy, Data.WIP_COST / (double) Data.WEEKS_PER_YEAR);
        facBackupCost.addTerm(backupFGPolicy, Data.FG_COST / (double) Data.WEEKS_PER_YEAR);

        // Production Cost
        int nbScenarios = scenarios.size();
        for (int i = 0; i < nbScenarios; i++) {
            scenarioToEstCost.put(scenarios.get(i), scenarioCost[i]);
        }
        avgProdutionCost = master.prod(1.0 / nbScenarios, master.sum(scenarioCost));
        risk = buildRisk();
        master.addMinimize(master.sum(facBackupCost, avgProdutionCost, risk), "TotalCost");
    }

    private IloNumExpr buildRisk() throws IloException {
        int nbScenarios = scenarios.size();
        IloNumExpr risk;
        switch (riskMeasure) {
            case robust:
                risk = master.numExpr();
                for (int i = 0; i < nbScenarios; i++) {
                    IloNumExpr sum = master.negative(scenarioCost[i]);
                    for (int j = 0; j < nbScenarios; j++) {
                        sum = master.diff(master.prod(1.0 / nbScenarios, scenarioCost[j]), scenarioCost[i]);
                    }
                    risk = master.sum(risk, master.prod(Data.RISK_AVERSION_FACTOR / nbScenarios, master.prod(sum, sum)));
                }
                break;
            case variabilityIdx:
                risk = master.prod(Data.VARIAB_IDX_PEANLTY / nbScenarios, master.sum(scenarioRisk));
                break;
            case probFinancialRisk:
                risk = master.prod(Data.PROB_FINANCIAL_RISK / nbScenarios, master.sum(scenarioRisk));
                break;
            case downsideRisk:
                risk = master.prod(Data.DOWNSIDE_RISK_PENALTY / nbScenarios, master.sum(scenarioRisk));
                break;
            default:
                risk = master.linearNumExpr(0);
                break;
        }
        return risk;
    }

    /**
     * Risk Measures Constraints
     * 
     * @throws IloException
     */
    private void buildDownsideRiskConstraints() throws IloException {
        int nbScenarios = scenarios.size();
        for (int i = 0; i < nbScenarios; i++) {
            switch (riskMeasure) {
                case variabilityIdx:
                    risk = master.addGe(scenarioRisk[i], master.diff(scenarioCost[i], avgProdutionCost),
                            "VariabilityIdx_" + i);
                    break;
                case probFinancialRisk:
                    master.addLe(scenarioCost[i], master.sum(Data.COST_TARGET, master.prod(scenarioRisk[i], 1e6)),
                            "ProbabilisticFinancialRisk_" + i);
                    master.addGe(scenarioCost[i],
                            master.sum(Data.COST_TARGET, master.prod(master.diff(1.0, scenarioRisk[i]), -1e6)),
                            "ProbabilisticFinancialRisk2_" + i);
                    break;
                case downsideRisk:
                    risk = master.addGe(scenarioRisk[i], master.diff(scenarioCost[i], Data.COST_TARGET),
                            "downsideRiskCtr_" + i);
                    break;
                default:
                    return;
            }
        }
    }


    /**
     * Master problem constraints include:
     * <ul>
     * <li>For each facility, link backup policy election to capacity covered and delay incurred</li>
     * <li>Only one policy per facility must be chosen</li>
     * <li>For each backup policy, link the delayed capacity incurred with the corresponding variable</li>
     * <li>Only one policy</li>
     * </ul>
     */
    private void buildMasterBackupConstraints() throws IloException {
        buildBackupOptions();
        buildOnePolicyConstraints();
        buildOptionsDelay();
        buildOneOptionCapacityConstraints();
        buildOneDelayConstraints();
    }

    private void buildBackupOptions() throws IloException {
        buildSupplierBackupOptions();
        buildPlantBackupOptions();
        buildDCBackupOptions();
    }

    private void buildSupplierBackupOptions() throws IloException {
        // Supplier Backup Options
        for (BackupOption option : Data.SUPPLIER_OPTIONS) {
            IloNumVar backupActiveVar = backupSupplierPolicy[option.getIndex()];
            master.addEq(backupSupplierOptionCapacity[option.getIndex()],
                    master.prod(SUPPLIER_CAPACITY * option.getCapacity(), backupActiveVar));
            master.addEq(backupSupplierOptionDelay[option.getIndex()], master.prod(option.getResponseTimes(), backupActiveVar));
        }
    }


    private void buildPlantBackupOptions() throws IloException {
        // Plant Backup options
        for (BackupOption option : Data.PLANT_OPTIONS) {
            IloNumVar backupActiveVar = backupPlantPolicy[option.getIndex()];
            master.addEq(backupPlantOptionCapacity[option.getIndex()], master.prod(PLANT_CAPACITY * option.getCapacity(), backupActiveVar));
            master.addEq(backupPlantOptionDelay[option.getIndex()], master.prod(option.getResponseTimes(), backupActiveVar));
        }
    }

    private void buildDCBackupOptions() throws IloException {
        // DC Backup Options
        for (BackupOption option : Data.DC_OPTIONS) {
            IloNumVar backUpActiveVar = backupDCPolicy[option.getIndex()];
            master.addLe(backupDCOptionCapacity[option.getIndex()], master.prod(MEAN_DEMAND * option.getCapacity(), backUpActiveVar));
            master.addEq(backupDCOptionDelay[option.getIndex()], master.prod(option.getResponseTimes(), backUpActiveVar));
        }
    }

    /**
     * Only 1 policy should be chosen
     */
    private void buildOnePolicyConstraints() throws IloException {
        master.addEq(master.sum(backupSupplierPolicy), 1.0, "OneSupplierPolicy");
        master.addEq(master.sum(backupPlantPolicy), 1.0, "OneSupplierPolicy");
        master.addEq(master.sum(backupDCPolicy), 1.0, "OneSupplierPolicy");
    }


    /**
     * Link delayed capacity with each posible option
     */
    private void buildOptionsDelay() throws IloException {
        for (int[] sameDelayOptions : Arrays.asList(new int[] {NO_DELAY_OPTION}, ONE_WEEK_DELAY_OPTION, TWO_WEEK_DELAY_OPTION,
                new int[] {FOUR_WEEKS_DELAY_OPTION}, new int[] {SIX_WEEKS_DELAY_OPTION})) {
            for (int j : sameDelayOptions) {
                int delay = SUPPLIER_OPTIONS[j].getResponseTimes();

                if (delay < MAX_DELAY) {
                    master.addLe(master.sum(backupSupplierDelayedCapacity, delay, MAX_DELAY - delay),
                            master.prod(SUPPLIER_CAPACITY, master.diff(1.0, backupSupplierPolicy[j])));
                    master.addLe(master.sum(backupPlantDelayedCapacity, delay, MAX_DELAY - delay),
                            master.prod(PLANT_CAPACITY, master.diff(1.0, backupPlantPolicy[j])));
                    master.addLe(master.sum(backupDCDelayedCapacity, delay, MAX_DELAY - delay),
                            master.prod(MEAN_DEMAND, master.diff(1.0, backupDCPolicy[j])));
                }

                for (int i = 0; i < delay && i < MAX_DELAY; i++) {
                    master.addGe(backupSupplierDelayedCapacity[i],
                            master.prod(SUPPLIER_CAPACITY * SUPPLIER_OPTIONS[j].getCapacity(), backupSupplierPolicy[j]));
                    master.addGe(backupPlantDelayedCapacity[i],
                            master.prod(PLANT_CAPACITY * PLANT_OPTIONS[j].getCapacity(), backupPlantPolicy[j]));
                    master.addGe(backupDCDelayedCapacity[i], master.prod(MEAN_DEMAND * DC_OPTIONS[j].getCapacity(), backupDCPolicy[j]));
                }
            }
        }
    }

    /**
     * Capacity must be the one of the chosen option
     */
    private void buildOneOptionCapacityConstraints() throws IloException {
        master.addEq(backupSupplierCapacity, master.sum(backupSupplierOptionCapacity), "oneBackupSupplierCapacity");
        master.addEq(backupPlantCapacity, master.sum(backupPlantOptionCapacity), "oneBackupPlantCapacity");
        master.addEq(backupDCCapacity, master.sum(backupDCOptionCapacity), "oneBackupDCCapacity");
    }

    /**
     * Delayed response must be the one of the chosen option
     */
    private void buildOneDelayConstraints() throws IloException {
        master.addEq(backupSupplierDelay, master.sum(backupSupplierOptionDelay), "oneBackupSupplierDelay");
        master.addEq(backupPlantDelay, master.sum(backupPlantOptionDelay), "oneBackupSupplierDelay");
        master.addEq(backupDCDelay, master.sum(backupDCOptionDelay), "oneBackupSupplierDelay");
    }



    /**
     * BendersCallback implements a lazy constraint callback that uses backup policy selection decisions in a proposed new incumbent
     * solution to the master problem to solve the subproblem.
     * 
     * If the subproblem is optimized and the subproblem objective cost matches the incumbent's estimated production cost (to within
     * rounding tolerance), the callback exits without adding a constraint (resulting in the incumbent being accepted in the master
     * problem).
     * 
     * If the subproblem is optimized but the incumbent's estimated production cost underestimates the true production cost, the callback
     * adds an optimality cut (eliminating the proposed incumbent).
     * 
     * If the subproblem proves to be infeasible, the callback adds a feasibility cut (eliminating the proposed incumbent).
     * 
     * Those should be the only possible outcomes. If something else happens (subproblem unbounded or unsolved), the callback writes a
     * message to stderr and punts.
     */
    class BendersCallback extends IloCplex.LazyConstraintCallback {

        // TODO Bunching of subproblems
        private List<MasterLinkedSubproblem> subproblems;
        private int nbIters = 0;

        public BendersCallback(List<RandomScenario> scenarios) {
            this.subproblems = new ArrayList<>();
            long start = System.currentTimeMillis();
            for (RandomScenario scenario : scenarios) {
                MasterLinkedSubproblem subprobem = new MasterLinkedSubproblem(MulticutLShaped.this, null, scenario);
                subproblems.add(subprobem);
            }
            long end = System.currentTimeMillis();
            System.out.println("*** Time to build subproblems " + subproblems.size() + " subproblems = " + (end - start) + " ms. ***");
        }

        @Override
        protected void main() throws IloException {
            nbIters++;

            long start = System.currentTimeMillis();
            BackupPoliciyData policyData = buildPolicyData();
            Map<MasterLinkedSubproblem, IloCplex.Status> subproblemToStatus = solveSubproblems(policyData);
            long end = System.currentTimeMillis();
            System.out.println("*** Solved " + subproblems.size() + " subproblems = " + (end - start) + " ms. (" + (end - start)
                    / (double) subproblems.size() + "ms/p) ***");

            Collection<IloRange> optimalityCuts = new ArrayList<IloRange>();
            for (Entry<MasterLinkedSubproblem, IloCplex.Status> entry : subproblemToStatus.entrySet()) {
                // solve the subproblem
                MasterLinkedSubproblem subproblem = entry.getKey();
                IloCplex.Status status = entry.getValue();
                IloNumVar estProdCost = scenarioToEstCost.get(subproblem.getScenario());

                // If scenario is infeasible we add the feasibility cut and return to the master adding no optimality cuts
                if (status.equals(IloCplex.Status.Infeasible)) {
                    IloRange range = createFeasibilityCut(subproblem);
                    add(range);
                    System.out.println(">>> Adding feasibility cut: " + range);
                    return;
                } else if (status.equals(IloCplex.Status.Optimal)) {
                    // get master production cost estimate
                    double zMaster = getValue(estProdCost);
                    if (zMaster < subproblem.getObjValue() - FUZZ) {
                        // the master problem surrogate variable underestimates the actual
                        IloRange range = createOptimalityCut(subproblem, estProdCost);
                        optimalityCuts.add(range);
                    }
                } else {
                    // unexpected status -- report but do nothing
                    System.err.println("!!! Unexpected subproblem solution status: " + status);
                }
            }
            for (IloRange range : optimalityCuts) {
                add(range);
            }

            System.out.println(">>> Added " + optimalityCuts.size() + " optimality cuts");


            // the master and subproblem production costs match
            // -- record the one subproblem production in case this proves to be the 
            //    winner (saving us from having to solve the LP one more time
            //    once the master terminates)
            if (optimalityCuts.isEmpty()) {
                System.out.println(">>> Accepting new incumbent with value " + getObjValue());
                solution = subproblems.get(0).recordSolution();
            }

        }

        private Map<MasterLinkedSubproblem, IloCplex.Status> solveSubproblems(BackupPoliciyData policyData) throws IloException {
            Map<MasterLinkedSubproblem, IloCplex.Status> subproblemToStatus = new HashMap<MasterLinkedSubproblem, IloCplex.Status>();
            for (MasterLinkedSubproblem subproblem : subproblems) {
                // Values of the master variables must accesed through the callback as the master has no solution yet
                subproblem.updateRHS(policyData);

                IloCplex.Status status = subproblem.solve();
                subproblemToStatus.put(subproblem, status);
                if (status.equals(IloCplex.Status.Infeasible)) {
                    break;
                }
                if (LOG.isDebugEnabled()) {
                    subproblem.export();
                }
            }
            return subproblemToStatus;
        }

        private IloRange createOptimalityCut(MasterLinkedSubproblem subproblem, IloNumVar estProdCost) throws UnknownObjectException,
                IloException {
            IloNumExpr expr = master.numExpr();

            // compute the scalar product of the RHS of constraints with the duals for those constraints
            for (IloRange range : subproblem.getConstraints()) {
                double dual = subproblem.getDual(range);
                expr = master.sum(expr, master.prod(dual, subproblem.getRHS(range)));
            }

            // add the optimality cut
            return (IloRange) master.ge(estProdCost, expr);
        }

        private IloRange createFeasibilityCut(MasterLinkedSubproblem subproblem) throws IloException {
            IloNumExpr expr = master.numExpr();

            // subproblem is infeasible -- add a feasibility cut
            // first step: get a Farkas certificate, corresponding to a dual ray
            // along which the dual is unbounded
            IloConstraint[] constraints = new IloConstraint[subproblem.getNbConstriants()];
            double[] coefficients = new double[subproblem.getNbConstriants()];
            subproblem.dualFarkas(constraints, coefficients);
            double temp = 0; // sum of cut terms not involving primal variables
            // process all elements of the Farkas certificate
            for (int i = 0; i < constraints.length; i++) {
                IloConstraint c = constraints[i];
                expr = master.sum(expr, master.prod(coefficients[i], subproblem.getRHS(c)));
            }
            // add a feasibility cut
            return master.le(master.sum(temp, expr), 0);
        }

        public List<MasterLinkedSubproblem> getSubproblems() {
            return subproblems;
        }

        private BackupPoliciyData buildPolicyData() throws IloException {
            return new BackupPoliciyData(getValue(backupFGPolicy),
                    getValue(backupWIPPolicy),
                    getValue(backupSupplierCapacity),
                    getValue(backupPlantCapacity),
                    getValue(backupDCCapacity),
                    getValues(backupSupplierDelayedCapacity),
                    getValues(backupPlantDelayedCapacity),
                    getValues(backupDCDelayedCapacity));
        }


    }

    /**
     * Solves the Benders master model.
     * 
     * @return the solution (in an instance of Solution)
     * @throws IloException
     *         if CPLEX encounters problems
     */

    public void solve() throws IloException {
        buildModel(scenarios);
        configureMasterSolver();
        solveModel();
        exportModels();
    }

    /**
     * Exports models to file to work with them
     */
    private void exportModels() throws IloException {
        if (LOG.isDebugEnabled()) {
            master.exportModel("ScreamBenders.lp");
            master.exportModel("ScreamBenders.sav");
            master.writeParam("ScreamBenders.prm");
        }
    }

    /**
     * Solves model and records solution
     */
    private void solveModel() throws IloException, UnknownObjectException {
        if (master.solve()) {
            System.out.println("Nb of InnerIters = " + bendersCallback.nbIters);
            recordSolution();
        }
    }

    /**
     * Records Master problem policy and first subproblem distribution policy
     */
    private void recordSolution() throws IloException, UnknownObjectException {
        BackupPoliciyData policyData = buildPolicyData();
        bendersCallback.getSubproblems().get(0).updateRHS(policyData);
        bendersCallback.getSubproblems().get(0).solve();
        solution = bendersCallback.getSubproblems().get(0).recordSolution();


        solution.totalCost = master.getBestObjValue();

        solution.facBackupCost = master.getValue(facBackupCost);
        solution.policyData = policyData;
        BackupOption supplierOption = getOption(Data.SUPPLIER_OPTIONS, master.getValues(backupSupplierPolicy));
        BackupOption plantOption = getOption(Data.PLANT_OPTIONS, master.getValues(backupPlantPolicy));
        BackupOption dcOption = getOption(Data.DC_OPTIONS, master.getValues(backupDCPolicy));

        solution.backupPolicy = new BackupPolicy(supplierOption.getIndex(),
                (int) master.getValue(backupWIPPolicy),
                plantOption.getIndex(),
                (int) master.getValue(backupFGPolicy),
                dcOption.getIndex());
    }

    /**
     * Free native solvers
     */
    public void end() {
        master.end();
        for (MasterLinkedSubproblem subproblem : bendersCallback.getSubproblems()) {
            subproblem.end();
        }
    }

    public Solution getSolution() {
        return solution;
    }

    // ----------- Aux Methods -----------

    private String[] buildNames(String string, int elements) {
        String[] names = new String[elements];
        for (int i = 0; i < names.length; i++) {
            names[i] = string.concat(String.valueOf(i));
        }
        return names;
    }

    private BackupOption getOption(BackupOption[] supplierOptions, double[] supplierOptionValues) {
        BackupOption choosenOption = supplierOptions[0];
        for (int i = 0; i < supplierOptionValues.length && choosenOption.equals(supplierOptions[0]); i++) {
            if (supplierOptionValues[i] > 0.5) {
                choosenOption = supplierOptions[i];
            }
        }
        return choosenOption;
    }

    private BackupPoliciyData buildPolicyData() throws IloException {
        return new BackupPoliciyData(master.getValue(backupFGPolicy),
                master.getValue(backupWIPPolicy),
                master.getValue(backupSupplierCapacity),
                master.getValue(backupPlantCapacity),
                master.getValue(backupDCCapacity),
                master.getValues(backupSupplierDelayedCapacity),
                master.getValues(backupPlantDelayedCapacity),
                master.getValues(backupDCDelayedCapacity));
    }

}
