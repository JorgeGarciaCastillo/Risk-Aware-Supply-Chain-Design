package org.scx.model;

import static org.scx.Data.BASE_DC_STOCK_LEVEL;
import static org.scx.Data.DESIRED_IFR;
import static org.scx.Data.FG_COST;
import static org.scx.Data.FG_PRICE;
import static org.scx.Data.IRR;
import static org.scx.Data.MAX_DELAY;
import static org.scx.Data.PLANT_CAPACITY;
import static org.scx.Data.SUPPLIER_CAPACITY;
import static org.scx.Data.WEEKS_PER_YEAR;
import static org.scx.Data.WIP_COST;

import java.util.Arrays;
import java.util.concurrent.Callable;

import org.scx.Data;
import org.scx.Solution;
import org.scx.Solution.BackupPoliciyData;
import org.scx.sample.RandomScenario;

import ilog.concert.IloException;
import ilog.concert.IloNumExpr;
import ilog.concert.IloNumVar;
import ilog.cplex.IloCplex;
import ilog.cplex.IloCplex.Status;

/**
 * Subproblem for each sample scenario
 */
public class ScenarioProblem implements Callable<IloCplex.Status> {

    protected final RandomScenario scenario;
    protected final IloCplex sub;

    /*
     * SCENARIO FIXED VARIABLES
     */

    protected BackupPoliciyData policy;
    protected IloNumExpr[] randomDemand;

    /*
     * SUBPROBLEM COST
     */

    // Cost components
    protected IloNumExpr totalLostSales;
    protected IloNumExpr invCarryCost;
    protected IloNumExpr desiredIFRSlack;

    protected IloNumExpr avgWIPInv;
    protected IloNumExpr avgFGInv;


    /*
     * SUBPROBLEM VARIABLES
     */

    // Sales slack
    protected IloNumVar[] lostSales;

    // Production from each facility
    protected IloNumVar[] supplierProd;
    protected IloNumVar[] plantProd;
    protected IloNumVar[] dcTransfer;

    // Inventory
    protected IloNumExpr wipStock;
    protected IloNumVar[] fgStock;

    // Net production of each type of goods
    protected IloNumVar[] wip;
    protected IloNumVar[] fgToDC;
    protected IloNumVar[] fgToCustomer;

    // BackupStock usage in each period
    protected IloNumVar[] backupWIPTransfer;
    protected IloNumVar[] backupFGTransfer;

    // Backup Stock in each period
    protected IloNumVar[] backupSupplier;
    protected IloNumVar[] backupPlant;
    protected IloNumVar[] backupDC;
    protected IloNumVar[] backupWIPStock;
    protected IloNumVar[] backupFGStock;

    // Maps each constraint to its right-hand side
    protected boolean modelBuilt;


    /**
     * Constructs SubProblem for Scenario
     * 
     * @param masterProblem
     * @param s
     * @throws IloException
     */
    public ScenarioProblem(BackupPoliciyData policy, RandomScenario s) {
        try {
            sub = new IloCplex();
            scenario = s;
            this.policy = policy;
        } catch (IloException e) {
            throw new IllegalStateException("Error building subproblem " + s + " :\n" + e);
        }
    }


    /**
     * Builds the subproblem for a scenario and a given backup policy
     */
    public void buildSubProblem() throws IloException {
        if (!modelBuilt) {
            buildVariablesAndConstraints();
            configureSolver();
            modelBuilt = true;
        }
    }


    private void buildVariablesAndConstraints() throws IloException {
        buildRandomDemandRHS();
        buildSubproblemVariables();
        buildSubproblemObjective();
        buildSupplyStatus();
        buildAvailability();
        buildDemand();
        buildStockBalance();
        buildNoTransferIfNoDisruption();
        buildDesiredItemFillRatio();
    }


    private void configureSolver() throws IloException {
        // disable presolving of the subproblem (if the presolver realizes the
        // subproblem is infeasible, we do not get a dual ray)
        sub.setParam(IloCplex.BooleanParam.PreInd, false);
        // We suppress subproblems solver log to avoid confussion
        sub.setOut(null);
    }


    private void buildRandomDemandRHS() throws IloException {
        randomDemand = new IloNumExpr[scenario.getRandomDemand().length];
        for (int i = 0; i < randomDemand.length; i++) {
            randomDemand[i] = sub.linearIntExpr(scenario.getRandomDemand()[i]);
        }
    }

    private void buildSubproblemVariables() throws IloException {
        lostSales = sub.numVarArray(WEEKS_PER_YEAR, 0.0, Double.MAX_VALUE, buildNames("lostSales", WEEKS_PER_YEAR));
        desiredIFRSlack = sub.numVar(0.0, Double.MAX_VALUE, "desiredIFRSlack");

        supplierProd = sub.numVarArray(WEEKS_PER_YEAR, 0.0, Double.MAX_VALUE, buildNames("supplierProd", WEEKS_PER_YEAR));
        plantProd = sub.numVarArray(WEEKS_PER_YEAR, 0.0, Double.MAX_VALUE, buildNames("plantProd", WEEKS_PER_YEAR));
        dcTransfer = sub.numVarArray(WEEKS_PER_YEAR, 0.0, Double.MAX_VALUE, buildNames("dcTransfer", WEEKS_PER_YEAR));

        // Max stock is base stock level
        fgStock = sub.numVarArray(WEEKS_PER_YEAR, 0.0, Double.MAX_VALUE, buildNames("fgStock", WEEKS_PER_YEAR));

        wip = sub.numVarArray(WEEKS_PER_YEAR, 0.0, Double.MAX_VALUE, buildNames("wip", WEEKS_PER_YEAR));
        fgToDC = sub.numVarArray(WEEKS_PER_YEAR, 0.0, Double.MAX_VALUE, buildNames("fgToDC", WEEKS_PER_YEAR));
        fgToCustomer = sub.numVarArray(WEEKS_PER_YEAR, 0.0, Double.MAX_VALUE, buildNames("fgToSell", WEEKS_PER_YEAR));

        backupWIPTransfer = sub.numVarArray(WEEKS_PER_YEAR, 0.0, Double.MAX_VALUE, buildNames("backupWIPTransfer", WEEKS_PER_YEAR));
        backupFGTransfer = sub.numVarArray(WEEKS_PER_YEAR, 0.0, Double.MAX_VALUE, buildNames("backupFGTransfer", WEEKS_PER_YEAR));

        backupWIPStock = sub.numVarArray(WEEKS_PER_YEAR, 0.0, Double.MAX_VALUE, buildNames("backupWIPStock", WEEKS_PER_YEAR));
        backupFGStock = sub.numVarArray(WEEKS_PER_YEAR, 0.0, Double.MAX_VALUE, buildNames("backupFGStock", WEEKS_PER_YEAR));
        backupSupplier = sub.numVarArray(WEEKS_PER_YEAR, 0.0, Double.MAX_VALUE, buildNames("backupSupplier", WEEKS_PER_YEAR));
        backupPlant = sub.numVarArray(WEEKS_PER_YEAR, 0.0, Double.MAX_VALUE, buildNames("backupPlant", WEEKS_PER_YEAR));
        backupDC = sub.numVarArray(WEEKS_PER_YEAR, 0.0, Double.MAX_VALUE, buildNames("backupDC", WEEKS_PER_YEAR));
    }

    private void buildSubproblemObjective() throws IloException {
        wipStock = sub.constant(Data.SUPPLIER_CAPACITY * (WEEKS_PER_YEAR - scenario.getSupplierDisruptionDuration()));
        avgWIPInv = sub.prod(1.0 / WEEKS_PER_YEAR, sub.sum(wipStock, sub.sum(backupWIPStock), sub.sum(backupSupplier)));
        avgFGInv = sub.prod(1.0 / WEEKS_PER_YEAR, sub.sum(sub.sum(fgStock), sub.sum(backupFGStock), sub.sum(backupDC)));

        // InvCarrtCost = InternalRateOfReturn * ((WIP_Cost * WIP_Stock) + (FG_Cost*FG_Stock))
        invCarryCost = sub.prod(IRR, sub.sum(sub.prod(WIP_COST, avgWIPInv), sub.prod(FG_COST, avgFGInv)));

        totalLostSales = sub.prod((FG_PRICE - FG_COST), sub.sum(lostSales));

        // Minimize ProductionCost
        sub.addMinimize(sub.sum(invCarryCost, totalLostSales, sub.prod(FG_PRICE, desiredIFRSlack)), "ProductionCost");
    }

    protected void buildSupplyStatus() throws IloException {
        for (int i = 0; i < Data.WEEKS_PER_YEAR; i++) {
            sub.addLe(supplierProd[i], scenario.isSupplierDisrupted(i) ? 0 : SUPPLIER_CAPACITY);
            sub.addLe(plantProd[i], scenario.isPlantDisrupted(i) ? 0 : PLANT_CAPACITY);
            sub.addLe(dcTransfer[i], scenario.isDcDisrupted(i) ? 0 : BASE_DC_STOCK_LEVEL);
        }
    }

    protected void buildAvailability() throws IloException {
        for (int i = 0; i < Data.WEEKS_PER_YEAR; i++) {
            // WIP availability - backupSupplier
            sub.addEq(sub.sum(supplierProd[i], backupWIPTransfer[i], backupSupplier[i], sub.negative(wip[i])), 0, "WIP" + i);

            // Supplier RHS Balance
            double ctrRHS =
                    scenario.isSupplierDisrupted(i) ? ((i - scenario.getSupplierDisruptionStart()) >= MAX_DELAY)
                            ? policy.backupSupplierCapacity
                            : policy.backupSupplierCapacity
                                    - policy.backupSupplierDelayedCapacity[i - scenario.getSupplierDisruptionStart()]
                            : 0;
            sub.addEq(backupSupplier[i], ctrRHS, "BackupSupplierBalance" + i);

            // FG to DC availability - backupPlant
            sub.addEq(sub.sum(plantProd[i], backupPlant[i], sub.negative(fgToDC[i])), 0, "FGtoDC" + i);

            // Plant RHS Balance
            ctrRHS =
                    scenario.isPlantDisrupted(i) ? ((i - scenario.getPlantDisruptionStart()) >= MAX_DELAY)
                            ? policy.backupPlantCapacity
                            : policy.backupPlantCapacity
                                    - policy.backupPlantDelayedCapacity[i - scenario.getPlantDisruptionStart()]
                            : 0;
            sub.addEq(backupPlant[i], ctrRHS, "BackupPlantBalance" + i);


            // FG to Customer availability - backupDC
            sub.addEq(sub.sum(dcTransfer[i], backupFGTransfer[i], backupDC[i], sub.negative(fgToCustomer[i])), 0, "FGtoCustomer"
                    + i);

            // DC RHS Balance
            ctrRHS =
                    scenario.isDcDisrupted(i) ? ((i - scenario.getDcDisruptionStart()) >= MAX_DELAY)
                            ? policy.backupDCCapacity
                            : policy.backupDCCapacity
                                    - policy.backupDCDelayedCapacity[i - scenario.getDcDisruptionStart()]
                            : 0;
            sub.addEq(backupDC[i], ctrRHS, "BackupDCBalance" + i);
        }
    }

    protected void buildDemand() throws IloException {
        // Demand Constraints -- record the constraints for use later
        for (int i = 0; i < Data.WEEKS_PER_YEAR; i++) {
            sub.addGe(sub.sum(fgToCustomer[i], lostSales[i]), scenario.getRandomDemand()[i], "Demand" + i);
        }
    }

    protected void buildStockBalance() throws IloException {
        // Stock Balance constraints
        for (int i = 0; i < Data.WEEKS_PER_YEAR; i++) {
            sub.addLe(fgStock[i], Data.BASE_DC_STOCK_LEVEL, "initialFgSafety");
        }

        sub.addEq(sub.diff(plantProd[0], wip[0]), 0, "WIPStockBalance" + 0);
        sub.addEq(sub.diff(fgToDC[0], sub.sum(fgStock[0], dcTransfer[0])), 0, "FGStockBalance" + 0);

        // WIP Stock Policy
        sub.addEq(backupWIPStock[0], 0.0, "initialWIPStock");

        // FG Stock Policy
        sub.addEq(backupFGStock[0], 0.0, "initialFGStock");


        for (int i = 1; i < Data.WEEKS_PER_YEAR; i++) {
            // WIP Stock balance
            sub.addEq(sub.diff(plantProd[i], wip[i]), 0, "WIPStockBalance" + i);

            // FG Stock balance
            sub.addEq(sub.sum(fgToDC[i], fgStock[i - 1], sub.negative(fgStock[i]), sub.negative(dcTransfer[i])), 0,
                    "FGStockBalance" + i);
            // WIP BackUp balance
            sub.addEq(sub.sum(backupWIPStock[i - 1], sub.negative(backupWIPTransfer[i]), sub.negative(backupWIPStock[i])), 0,
                    "WIPBackupBalance" + i);

            // FG Backup balance
            sub.addEq(sub.sum(backupFGStock[i - 1], sub.negative(backupFGTransfer[i]), sub.negative(backupFGStock[i])), 0,
                    "FGBackupBalance" + i);
        }
    }

    protected void buildNoTransferIfNoDisruption() throws IloException {
        for (int i = 0; i < WEEKS_PER_YEAR; i++) {
            // No Backup transfer if no disruption
            if (!scenario.isSupplierDisrupted(i)) {
                sub.addLe(backupWIPTransfer[i], 0.0);
            }
            if (!scenario.isDcDisrupted(i) && !scenario.isPlantDisrupted(i)) {
                sub.addLe(backupFGTransfer[i], 0.0);
            }
        }
    }

    protected void buildDesiredItemFillRatio() throws IloException {
        double totalDemand = 0.0;
        for (double demand : scenario.getRandomDemand()) {
            totalDemand += demand;
        }
        sub.addGe(sub.sum(desiredIFRSlack, sub.sum(fgToCustomer)), DESIRED_IFR * totalDemand);
    }

    public IloCplex.Status solve() throws IloException {
        buildSubProblem();
        sub.solve();
        return sub.getStatus();
    }

    public Solution recordSolution() throws IloException {
        Solution s = new Solution();

        s.totalCost = sub.getObjValue();
        s.negativeRevenue = 0;
        s.avgLostSales = sub.getValue(totalLostSales);
        s.invCarryCost = sub.getValue(invCarryCost);

        s.supplierProd = copyValues(supplierProd);
        s.plantProd = copyValues(plantProd);
        s.dcTransfer = copyValues(dcTransfer);

        s.wip = copyValues(wip);
        s.fgToDC = copyValues(fgToDC);
        s.fgToCustomer = copyValues(fgToCustomer);

        s.fgStock = copyValues(fgStock);
        s.lostSales = copyValues(lostSales);

        s.backupSupplier = copyValues(backupSupplier);
        s.backupPlant = copyValues(backupPlant);
        s.backupDC = copyValues(backupDC);

        s.backupWIP = copyValues(backupWIPStock);
        s.backupFG = copyValues(backupFGStock);

        s.backupWIPTransfer = copyValues(backupWIPTransfer);
        s.backupFGTransfer = copyValues(backupFGTransfer);

        return s;
    }

    public double[] copyValues(IloNumVar[] vars) throws IloException {
        return Arrays.copyOf(sub.getValues(vars), vars.length);
    }


    @Override
    public Status call() throws Exception {
        buildSubProblem();
        return solve();
    }

    public void end() {
        sub.end();
    }

    /**
     * Aux function to name arrays of constraints/variables for CPLEX
     * 
     * @param string
     * @param elements
     * @return
     */
    private String[] buildNames(String string, int elements) {
        String[] names = new String[elements];
        for (int i = 0; i < names.length; i++) {
            names[i] = string.concat(String.valueOf(i));
        }
        return names;
    }
}
