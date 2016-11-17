package org.scx.model;

import static java.lang.Double.MAX_VALUE;
import static java.lang.Double.MIN_VALUE;
import static org.scx.Data.DC_OPTIONS;
import static org.scx.Data.FOUR_DAYS_DELAY_OPTION;
import static org.scx.Data.MAX_DELAY;
import static org.scx.Data.MEAN_DEMAND;
import static org.scx.Data.NUM_BACKUP_POLICIES;
import static org.scx.Data.ONE_DAY_DELAY_OPTION;
import static org.scx.Data.PLANT_CAPACITY;
import static org.scx.Data.PLANT_OPTIONS;
import static org.scx.Data.SIX_DAYS_DELAY_OPTION;
import static org.scx.Data.SUPPLIER_CAPACITY;
import static org.scx.Data.SUPPLIER_OPTIONS;
import static org.scx.Data.TWO_DAYS_DELAY_OPTION;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.scx.Data;
import org.scx.Data.BackupOption;
import org.scx.model.Solution.BackupPolicy;
import org.scx.sample.RandomScenario;

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

    protected IloCplex master; // the master model
    private List<RandomScenario> scenarios;
    private List<ScenarioSubproblem> subproblems;

    /*
     * MASTER COST
     */

    // Backup Setup Cost
    protected IloNumExpr facBackupCost;

    // surrogate variable for production cost
    protected IloNumExpr avgProdutionCost;
    protected IloNumVar[] scenarioProductionCost;

    protected Map<ScenarioSubproblem, IloNumVar> scenarioToEstCost;

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
     */
    public MulticutLShaped(List<RandomScenario> scenarios) throws IloException {
        solution = new Solution();
        scenarioToEstCost = new LinkedHashMap<>();
        this.scenarios = scenarios;
        master = new IloCplex();

    }

    /**
     * Builds the master problem model which will decide the policy
     */
    private void buildModel(List<RandomScenario> scenarios) throws IloException {
        buildMasterVariables();
        buildSubProblem(scenarios);
        buildMasterObjective();
        buildMasterBackupConstraints();
    }

    /**
     * Builds the subproblem for a scenario and a given backup policy
     */
    private void buildSubProblem(List<RandomScenario> scenarios) throws IloException {
        subproblems = new ArrayList<>();
        for (RandomScenario scenario : scenarios) {
            ScenarioSubproblem subprobem = new ScenarioSubproblem(this, scenario);
            subproblems.add(subprobem);
        }
    }


    /**
     * Configure solver to solve subproblems when a feasible policy is found
     */
    private void configureMasterSolver() throws IloException {
        // attach a Benders callback to the master
        master.use(new BendersCallback(subproblems));

        // Avoid repeating presolve to not loose the solution
        master.setParam(IloCplex.IntParam.RepeatPresolve, 0);
    }

    /**
     * Creates de master problem variables: policy election and policy derived values
     * 
     */
    private void buildMasterVariables() throws IloException {
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
        IloLinearNumExpr expr = master.linearNumExpr();
        for (int i = 0; i < Data.NUM_BACKUP_POLICIES; i++) {
            expr.addTerm(backupSupplierPolicy[i], SUPPLIER_OPTIONS[i].getCost());
            expr.addTerm(backupPlantPolicy[i], PLANT_OPTIONS[i].getCost());
            expr.addTerm(backupDCPolicy[i], DC_OPTIONS[i].getCost());
        }
        expr.addTerm(backupWIPPolicy, Data.WIP_COST / (double) Data.WEEKS_PER_YEAR);
        expr.addTerm(backupFGPolicy, Data.FG_COST / (double) Data.WEEKS_PER_YEAR);

        facBackupCost = expr;

        // Production Cost
        int nbScenarios = subproblems.size();
        scenarioProductionCost = master.numVarArray(nbScenarios, MIN_VALUE, MAX_VALUE, buildNames("estProductionCost", nbScenarios));
        for (int i = 0; i < nbScenarios; i++) {
            scenarioToEstCost.put(subproblems.get(i), scenarioProductionCost[i]);
        }
        avgProdutionCost = master.prod(1.0 / nbScenarios, master.sum(scenarioProductionCost));

        // TODO Add regularization terms
        master.addMinimize(master.sum(avgProdutionCost, facBackupCost), "TotalCost");
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

    private void buildDCBackupOptions() throws IloException {
        // DC Backup Options
        for (BackupOption option : Data.DC_OPTIONS) {
            IloNumVar backUpActiveVar = backupDCPolicy[option.getIndex()];
            master.addLe(backupDCOptionCapacity[option.getIndex()], master.prod(MEAN_DEMAND * option.getCapacity(), backUpActiveVar));
            master.addEq(backupDCOptionDelay[option.getIndex()], master.prod(option.getResponseTimes(), backUpActiveVar));
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

    private void buildSupplierBackupOptions() throws IloException {
        // Supplier Backup Options
        for (BackupOption option : Data.SUPPLIER_OPTIONS) {
            IloNumVar backupActiveVar = backupSupplierPolicy[option.getIndex()];
            master.addEq(backupSupplierOptionCapacity[option.getIndex()],
                    master.prod(SUPPLIER_CAPACITY * option.getCapacity(), backupActiveVar));
            master.addEq(backupSupplierOptionDelay[option.getIndex()], master.prod(option.getResponseTimes(), backupActiveVar));
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
        buildNoDelayOptions();
        buildDelayForOneDayOptions();
        buildDelayForTwoDaysOptions();
        buildDelayForFourDaysOptions();
        buildDelayForSixDaysOptions();
    }

    /**
     * No delay options
     */
    private void buildNoDelayOptions() throws IloException {
        master.add(master.ifThen(master.ge(backupSupplierPolicy[0], 0.5), master.eq(master.sum(backupSupplierDelayedCapacity), 0)));
        master.add(master.ifThen(master.ge(backupPlantPolicy[0], 0.5), master.eq(master.sum(backupPlantDelayedCapacity), 0)));
        master.add(master.ifThen(master.ge(backupDCPolicy[0], 0.5), master.eq(master.sum(backupDCDelayedCapacity), 0)));
    }

    /**
     * Options with 1 day of delayed response
     */
    private void buildDelayForOneDayOptions() throws IloException {
        for (int i : ONE_DAY_DELAY_OPTION) {
            master.add(master.ifThen(
                    master.ge(backupSupplierPolicy[i], 0.5),
                    master.and(master.eq(master.sum(backupSupplierDelayedCapacity, 1, 5), 0),
                            master.eq(backupSupplierDelayedCapacity[0], SUPPLIER_CAPACITY * SUPPLIER_OPTIONS[i].getCapacity()))));

            master.add(master.ifThen(
                    master.ge(backupPlantPolicy[i], 0.5),
                    master.and(master.eq(master.sum(backupPlantDelayedCapacity, 1, 5), 0),
                            master.eq(backupPlantDelayedCapacity[0], PLANT_CAPACITY * PLANT_OPTIONS[i].getCapacity()))));

            master.add(master.ifThen(
                    master.ge(backupDCPolicy[i], 0.5),
                    master.and(master.eq(master.sum(backupDCDelayedCapacity, 1, 5), 0),
                            master.eq(backupDCDelayedCapacity[0], MEAN_DEMAND * DC_OPTIONS[i].getCapacity()))));
        }
    }

    /**
     * Options with 2 days of delayed response
     */
    private void buildDelayForTwoDaysOptions() throws IloException {
        for (int i : TWO_DAYS_DELAY_OPTION) {
            master.add(master.ifThen(
                    master.ge(backupSupplierPolicy[i], 0.5),
                    master.and(master.eq(master.sum(backupSupplierDelayedCapacity, 2, 4), 0),
                            master.and(master.eq(backupSupplierDelayedCapacity[0], SUPPLIER_CAPACITY * SUPPLIER_OPTIONS[i].getCapacity()),
                                    master.eq(backupSupplierDelayedCapacity[1], SUPPLIER_CAPACITY * SUPPLIER_OPTIONS[i].getCapacity())))));
            master.add(master.ifThen(
                    master.ge(backupPlantPolicy[i], 0.5),
                    master.and(master.eq(master.sum(backupPlantDelayedCapacity, 2, 4), 0),
                            master.and(master.eq(backupPlantDelayedCapacity[0], PLANT_CAPACITY * PLANT_OPTIONS[i].getCapacity()),
                                    master.eq(backupPlantDelayedCapacity[1], PLANT_CAPACITY * PLANT_OPTIONS[i].getCapacity())))));

            master.add(master.ifThen(
                    master.ge(backupDCPolicy[i], 0.5),
                    master.and(master.eq(master.sum(backupDCDelayedCapacity, 2, 4), 0),
                            master.and(
                                    master.eq(backupDCDelayedCapacity[0], MEAN_DEMAND * DC_OPTIONS[i].getCapacity()),
                                    master.eq(backupDCDelayedCapacity[1], MEAN_DEMAND * DC_OPTIONS[i].getCapacity())))));
        }
    }

    /**
     * Options with 4 days of delayed response
     */
    private void buildDelayForFourDaysOptions() throws IloException {

        IloConstraint[] supplierDelays = new IloConstraint[MAX_DELAY];
        IloConstraint[] plantDelays = new IloConstraint[MAX_DELAY];
        IloConstraint[] dcDelays = new IloConstraint[MAX_DELAY];

        for (int j = 0; j < 4; j++) {
            supplierDelays[j] =
                    master.eq(backupSupplierDelayedCapacity[j], SUPPLIER_CAPACITY * SUPPLIER_OPTIONS[FOUR_DAYS_DELAY_OPTION].getCapacity());
            plantDelays[j] = master.eq(backupPlantDelayedCapacity[j], PLANT_CAPACITY * PLANT_OPTIONS[FOUR_DAYS_DELAY_OPTION].getCapacity());
            dcDelays[j] = master.eq(backupDCDelayedCapacity[j], MEAN_DEMAND * DC_OPTIONS[FOUR_DAYS_DELAY_OPTION].getCapacity());


        }

        buildFourDaysDelay(backupSupplierPolicy, backupSupplierDelayedCapacity, supplierDelays);
        buildFourDaysDelay(backupPlantPolicy, backupPlantDelayedCapacity, plantDelays);
        buildFourDaysDelay(backupDCPolicy, backupDCDelayedCapacity, dcDelays);
    }

    private void buildFourDaysDelay(IloNumVar[] backupPolicy, IloNumVar[] backupDelayedCapacity,
            IloConstraint[] delays) throws IloException {
        master.add(master.ifThen(master.ge(backupPolicy[FOUR_DAYS_DELAY_OPTION], 0.5),
                master.and(master.eq(master.sum(backupDelayedCapacity, 4, 2), 0), master.and(delays))));

    }

    /**
     * Option with 6 days of delayed response
     */
    private void buildDelayForSixDaysOptions() throws IloException {
        IloConstraint[] supplierDelays = new IloConstraint[MAX_DELAY];
        IloConstraint[] plantDelays = new IloConstraint[MAX_DELAY];
        IloConstraint[] dcDelays = new IloConstraint[MAX_DELAY];

        for (int j = 0; j < MAX_DELAY; j++) {
            supplierDelays[j] =
                    master.eq(backupSupplierDelayedCapacity[j], SUPPLIER_CAPACITY * SUPPLIER_OPTIONS[SIX_DAYS_DELAY_OPTION].getCapacity());
            plantDelays[j] = master.eq(backupPlantDelayedCapacity[j], PLANT_CAPACITY * PLANT_OPTIONS[SIX_DAYS_DELAY_OPTION].getCapacity());
            dcDelays[j] = master.eq(backupDCDelayedCapacity[j], MEAN_DEMAND * DC_OPTIONS[SIX_DAYS_DELAY_OPTION].getCapacity());


        }
        master.add(master.ifThen(master.ge(backupSupplierPolicy[SIX_DAYS_DELAY_OPTION], 0.5), master.and(supplierDelays)));
        master.add(master.ifThen(master.ge(backupPlantPolicy[SIX_DAYS_DELAY_OPTION], 0.5), master.and(plantDelays)));
        master.add(master.ifThen(master.ge(backupDCPolicy[SIX_DAYS_DELAY_OPTION], 0.5), master.and(dcDelays)));
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
        private List<ScenarioSubproblem> subproblems;

        public BendersCallback(List<ScenarioSubproblem> subproblems) {
            this.subproblems = subproblems;
        }


        @Override
        protected void main() throws IloException {
            Collection<IloRange> optimalityCuts = new ArrayList<IloRange>();
            Map<ScenarioSubproblem, IloCplex.Status> subproblemToStatus = new HashMap<ScenarioSubproblem, IloCplex.Status>();
            for (ScenarioSubproblem subproblem : subproblems) {
                // Values of the master variables must accesed through the callback as the master has no solution yet
                subproblem.updateRHS(getValue(backupWIPPolicy),
                        getValue(backupFGPolicy),
                        getValue(backupSupplierCapacity),
                        getValues(backupSupplierDelayedCapacity),
                        getValue(backupPlantCapacity),
                        getValues(backupPlantDelayedCapacity),
                        getValue(backupDCCapacity),
                        getValues(backupDCDelayedCapacity));

                IloCplex.Status status = subproblem.solve();
                subproblemToStatus.put(subproblem, status);
                if (status.equals(IloCplex.Status.Infeasible)) {
                    break;
                }
            }

            for (Entry<ScenarioSubproblem, IloCplex.Status> entry : subproblemToStatus.entrySet()) {
                // solve the subproblem
                ScenarioSubproblem subproblem = entry.getKey();
                IloCplex.Status status = entry.getValue();
                IloNumVar estProdCost = scenarioToEstCost.get(subproblem);

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
                System.out.println(">>> Adding optimality cut: " + range);
            }

            // the master and subproblem production costs match
            // -- record the one subproblem production in case this proves to be the 
            //    winner (saving us from having to solve the LP one more time
            //    once the master terminates)
            if (optimalityCuts.isEmpty()) {
                System.out.println(">>> Accepting new incumbent with value " + getObjValue());
                solution = subproblems.get(0).recordSolution();
            }

        }

        private IloRange createOptimalityCut(ScenarioSubproblem subproblem, IloNumVar estProdCost) throws UnknownObjectException,
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

        private IloRange createFeasibilityCut(ScenarioSubproblem subproblem) throws IloException {
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

    public Solution getSolution() {
        return solution;
    }

    /**
     * Exports models to file to work with them
     */
    private void exportModels() throws IloException {
        master.exportModel("ScreamBenders.lp");
        master.exportModel("ScreamBenders.sav");
        master.writeParam("ScreamBenders.prm");
    }

    /**
     * Solves model and records solution
     */
    private void solveModel() throws IloException, UnknownObjectException {
        if (master.solve()) {
            subproblems.get(0).updateRHS(master.getValue(backupWIPPolicy),
                    master.getValue(backupFGPolicy),
                    master.getValue(backupSupplierCapacity),
                    master.getValues(backupSupplierDelayedCapacity),
                    master.getValue(backupPlantCapacity),
                    master.getValues(backupPlantDelayedCapacity),
                    master.getValue(backupDCCapacity),
                    master.getValues(backupDCDelayedCapacity));
            subproblems.get(0).solve();
            solution = subproblems.get(0).recordSolution();


            solution.totalCost = master.getObjValue();
            solution.facBackupCost = master.getValue(facBackupCost);
            solution.negativeRevenue = 0.0; // TODO
            BackupOption supplierOption = getOption(Data.SUPPLIER_OPTIONS, master.getValues(backupSupplierPolicy));
            BackupOption plantOption = getOption(Data.PLANT_OPTIONS, master.getValues(backupPlantPolicy));
            BackupOption dcOption = getOption(Data.DC_OPTIONS, master.getValues(backupDCPolicy));

            solution.backupPolicy = new BackupPolicy(supplierOption.getIndex(),
                    (int) master.getValue(backupWIPPolicy),
                    plantOption.getIndex(),
                    (int) master.getValue(backupFGPolicy),
                    dcOption.getIndex());
        }
        solution.status = master.getCplexStatus();
    }

    /**
     * Free native solvers
     */
    public void end() {
        master.end();
        endSubproblems();
    }

    public void endSubproblems() {
        for (ScenarioSubproblem subproblem : subproblems) {
            subproblem.end();
        }
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


}
