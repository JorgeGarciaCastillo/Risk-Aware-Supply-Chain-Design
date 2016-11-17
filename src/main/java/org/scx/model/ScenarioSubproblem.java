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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import org.scx.Data;
import org.scx.sample.RandomScenario;

import ilog.concert.IloConstraint;
import ilog.concert.IloException;
import ilog.concert.IloNumExpr;
import ilog.concert.IloNumVar;
import ilog.concert.IloRange;
import ilog.cplex.IloCplex;
import ilog.cplex.IloCplex.Status;

/**
 * Subproblem for each sample scenario
 */
public class ScenarioSubproblem implements Callable<IloCplex.Status> {

    private final RandomScenario scenario;
    private final IloCplex master;
    private final IloCplex sub;

    /*
     * MASTER LINK VARIABLES
     */

    private IloNumExpr[] randomDemand;

    private IloNumVar backupFGPolicy;
    private IloNumVar backupWIPPolicy;
    private IloNumVar backupSupplierCapacity;
    private IloNumVar backupPlantCapacity;
    private IloNumVar backupDCCapacity;

    private IloNumVar[] backupSupplierDelayedCapacity;
    private IloNumVar[] backupPlantDelayedCapacity;
    private IloNumVar[] backupDCDelayedCapacity;

    /*
     * SUBPROBLEM COST
     */

    // Cost components
    private IloNumExpr totalLostSales;
    private IloNumExpr invCarryCost;
    private IloNumExpr desiredIFRSlack;

    private IloNumExpr avgWIPInv;
    private IloNumExpr avgFGInv;


    /*
     * SUBPROBLEM VARIABLES
     */

    // Sales slack
    private IloNumVar[] lostSales;

    // Production from each facility
    private IloNumVar[] supplierProd;
    private IloNumVar[] plantProd;
    private IloNumVar[] dcTransfer;

    // Inventory
    private IloNumExpr wipStock;
    private IloNumVar[] fgStock;

    // Net production of each type of goods
    private IloNumVar[] wip;
    private IloNumVar[] fgToDC;
    private IloNumVar[] fgToCustomer;

    // BackupStock usage in each period
    private IloNumVar[] backupWIPTransfer;
    private IloNumVar[] backupFGTransfer;

    // Backup Stock in each period
    private IloNumVar[] backupSupplier;
    private IloNumVar[] backupPlant;
    private IloNumVar[] backupDC;
    private IloNumVar[] backupWIPStock;
    private IloNumVar[] backupFGStock;

    /*
     * To compute both optimality and feasibility cuts, we will need to multiply the right-hand sides of the subproblem constraints
     * (including both constant terms and terms involving master problem variables) by the corresponding subproblem dual values (obtained
     * either directly, if the subproblem is optimized, or via a Farkas certificate if the subproblem is infeasible). To facilitate the
     * computations, we will construct an instance of IloNumExpr for each right-hand side, incorporating both scalars and master variables.
     * 
     * Since CPLEX returns the Farkas certificate in no particular order (in particular, NOT in the order the subproblem constraints are
     * created), we need to be able to access the IloNumExpr instances in the same arbitrary order used by the Farkas certificate. To do
     * that, we will store them in a map keyed by the subproblem constraints themselves (which we will store in arrays).
     */
    private IloRange[] fgStockC;
    private IloRange[] supplierStatus;
    private IloRange[] plantStatus;
    private IloRange[] dcStatus;
    private IloRange[] wipAvailabilityC;
    private IloRange[] fgToDCAvailabilityC;
    private IloRange[] fgToCustomerAvailabilityC;
    private IloRange[] cDemand;
    private IloRange[] WIPBalanceC;
    private IloRange[] FGBalanceC;
    private IloRange[] backupWIPBalanceC;
    private IloRange[] backupFGBalanceC;
    private IloRange[] backupSupplierBalance;
    private IloRange[] backupPlantBalance;
    private IloRange[] backupDCBalance;

    // Maps each constraint to its right-hand side
    private Map<IloRange, IloNumExpr> rangeToRHS;
    private boolean modelBuilt;


    /**
     * Constructs SubProblem for Scenario
     * 
     * @param masterProblem
     *        TODO define clear interface between master and subproblem
     * @param s
     * @throws IloException
     */
    public ScenarioSubproblem(MulticutLShaped masterProblem, RandomScenario s) {
        try {
            sub = new IloCplex();
            master = masterProblem.master;
            rangeToRHS = new LinkedHashMap<>();

            scenario = s;
            backupFGPolicy = masterProblem.backupFGPolicy;
            backupWIPPolicy = masterProblem.backupWIPPolicy;
            backupSupplierCapacity = masterProblem.backupSupplierCapacity;
            backupPlantCapacity = masterProblem.backupPlantCapacity;
            backupDCCapacity = masterProblem.backupDCCapacity;

            backupSupplierDelayedCapacity = masterProblem.backupSupplierDelayedCapacity;
            backupPlantDelayedCapacity = masterProblem.backupPlantDelayedCapacity;
            backupDCDelayedCapacity = masterProblem.backupDCDelayedCapacity;

            buildSubProblem();
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
            modelBuilt = true;
        }
        configureSolver();
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
            randomDemand[i] = master.linearIntExpr(scenario.getRandomDemand()[i]);
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

    private void buildSupplyStatus() throws IloException {
        // Supply-status bounds
        supplierStatus = new IloRange[Data.WEEKS_PER_YEAR];
        plantStatus = new IloRange[Data.WEEKS_PER_YEAR];
        dcStatus = new IloRange[Data.WEEKS_PER_YEAR];
        for (int i = 0; i < Data.WEEKS_PER_YEAR; i++) {
            supplierStatus[i] = sub.addLe(supplierProd[i], scenario.isSupplierDisrupted(i) ? 0 : SUPPLIER_CAPACITY);
            rangeToRHS.put(supplierStatus[i], master.linearNumExpr(supplierStatus[i].getUB()));

            plantStatus[i] = sub.addLe(plantProd[i], scenario.isPlantDisrupted(i) ? 0 : PLANT_CAPACITY);
            rangeToRHS.put(plantStatus[i], master.linearNumExpr(plantStatus[i].getUB()));

            dcStatus[i] = sub.addLe(dcTransfer[i], scenario.isDcDisrupted(i) ? 0 : BASE_DC_STOCK_LEVEL);
            rangeToRHS.put(dcStatus[i], master.linearNumExpr(dcStatus[i].getUB()));
        }
    }

    private void buildAvailability() throws IloException {
        // Availability constraints
        // -- record the constraints for use later
        // -- also map each constraint to the corresponding binary variable in the
        //    master (for decoding Farkas certificates)
        wipAvailabilityC = new IloRange[WEEKS_PER_YEAR];
        fgToDCAvailabilityC = new IloRange[WEEKS_PER_YEAR];
        fgToCustomerAvailabilityC = new IloRange[WEEKS_PER_YEAR];

        backupSupplierBalance = new IloRange[WEEKS_PER_YEAR];
        backupPlantBalance = new IloRange[WEEKS_PER_YEAR];
        backupDCBalance = new IloRange[WEEKS_PER_YEAR];

        for (int i = 0; i < Data.WEEKS_PER_YEAR; i++) {
            // WIP availability - backupSupplier
            wipAvailabilityC[i] =
                    sub.addEq(sub.sum(supplierProd[i], backupWIPTransfer[i], backupSupplier[i], sub.negative(wip[i])), 0, "WIP" + i);
            rangeToRHS.put(wipAvailabilityC[i], master.linearIntExpr(0));

            // Supplier RHS Balance
            backupSupplierBalance[i] = sub.addEq(backupSupplier[i], 0.0, "BackupSupplierBalance" + i);
            IloNumExpr ctrRHS =
                    scenario.isSupplierDisrupted(i) ? ((i - scenario.getSupplierDisruptionStart()) >= MAX_DELAY)
                            ? backupSupplierCapacity
                            : master.sum(backupSupplierCapacity,
                                    master.negative(backupSupplierDelayedCapacity[i - scenario.getSupplierDisruptionStart()]))
                            : master.linearIntExpr(0);
            rangeToRHS.put(backupSupplierBalance[i], ctrRHS);

            // FG to DC availability - backupPlant
            fgToDCAvailabilityC[i] = sub.addEq(sub.sum(plantProd[i], backupPlant[i], sub.negative(fgToDC[i])), 0, "FGtoDC" + i);
            rangeToRHS.put(fgToDCAvailabilityC[i], master.linearIntExpr(0));

            // Plant RHS Balance
            backupPlantBalance[i] = sub.addEq(backupPlant[i], 0.0, "BackupPlantBalance" + i);
            ctrRHS = scenario.isPlantDisrupted(i) ? ((i - scenario.getPlantDisruptionStart()) >= MAX_DELAY)
                    ? backupPlantCapacity
                    : master.sum(backupPlantCapacity, master.negative(backupPlantDelayedCapacity[i - scenario.getPlantDisruptionStart()]))
                    : master.linearIntExpr(0);
            rangeToRHS.put(backupPlantBalance[i], ctrRHS);


            // FG to Customer availability - backupDC
            fgToCustomerAvailabilityC[i] =
                    sub.addEq(sub.sum(dcTransfer[i], backupFGTransfer[i], backupDC[i], sub.negative(fgToCustomer[i])), 0, "FGtoCustomer"
                            + i);
            rangeToRHS.put(fgToCustomerAvailabilityC[i], master.linearIntExpr(0));

            // DC RHS Balance
            backupDCBalance[i] = sub.addEq(backupDC[i], 0.0, "BackupDCBalance" + i);
            ctrRHS = scenario.isDcDisrupted(i) ? ((i - scenario.getDcDisruptionStart()) >= MAX_DELAY)
                    ? backupDCCapacity
                    : master.sum(backupDCCapacity, master.negative(backupDCDelayedCapacity[i - scenario.getDcDisruptionStart()]))
                    : master.linearIntExpr(0);

            rangeToRHS.put(backupDCBalance[i], ctrRHS);
        }
    }

    private void buildDemand() throws IloException {
        // Demand Constraints -- record the constraints for use later
        cDemand = new IloRange[Data.WEEKS_PER_YEAR];
        for (int i = 0; i < Data.WEEKS_PER_YEAR; i++) {
            cDemand[i] = sub.addGe(sub.sum(fgToCustomer[i], lostSales[i]), scenario.getRandomDemand()[i], "Demand" + i);
            rangeToRHS.put(cDemand[i], randomDemand[i]);
        }
    }

    private void buildStockBalance() throws IloException {
        // Stock Balance constraints
        fgStockC = new IloRange[WEEKS_PER_YEAR];
        for (int i = 0; i < Data.WEEKS_PER_YEAR; i++) {
            fgStockC[i] = sub.addLe(fgStock[0], Data.BASE_DC_STOCK_LEVEL, "initialFgSafety");
            rangeToRHS.put(fgStockC[i], master.linearNumExpr(fgStockC[i].getUB()));
        }

        WIPBalanceC = new IloRange[WEEKS_PER_YEAR];
        FGBalanceC = new IloRange[WEEKS_PER_YEAR];
        backupWIPBalanceC = new IloRange[WEEKS_PER_YEAR];
        backupFGBalanceC = new IloRange[WEEKS_PER_YEAR];

        WIPBalanceC[0] = sub.addEq(sub.sum(plantProd[0], sub.negative(wip[0])), 0, "WIPStockBalance" + 0);
        rangeToRHS.put(WIPBalanceC[0], master.linearIntExpr(0));

        FGBalanceC[0] = sub.addEq(sub.sum(fgToDC[0], sub.negative(fgStock[0]), sub.negative(dcTransfer[0])), 0, "FGStockBalance" + 0);
        rangeToRHS.put(FGBalanceC[0], master.linearIntExpr(0));

        // WIP Stock Policy
        backupWIPBalanceC[0] = sub.addEq(backupWIPStock[0], 0.0, "initialWIPStock");
        rangeToRHS.put(backupWIPBalanceC[0], backupWIPPolicy);

        // FG Stock Policy
        backupFGBalanceC[0] = sub.addEq(backupFGStock[0], 0.0, "initialFGStock");
        rangeToRHS.put(backupFGBalanceC[0], backupFGPolicy);


        for (int i = 1; i < Data.WEEKS_PER_YEAR; i++) {
            // WIP Stock balance
            WIPBalanceC[i] = sub.addEq(sub.sum(plantProd[i], sub.negative(wip[i])), 0, "WIPStockBalance" + i);
            rangeToRHS.put(WIPBalanceC[i], master.linearIntExpr(0));

            // FG Stock balance
            FGBalanceC[i] =
                    sub.addEq(sub.sum(fgToDC[i], fgStock[i - 1], sub.negative(fgStock[i]), sub.negative(dcTransfer[i])), 0,
                            "FGStockBalance" + i);
            rangeToRHS.put(FGBalanceC[i], master.linearIntExpr(0));

            // WIP BackUp balance
            backupWIPBalanceC[i] =
                    sub.addEq(sub.sum(backupWIPStock[i - 1], sub.negative(backupWIPTransfer[i]), sub.negative(backupWIPStock[i])), 0,
                            "WIPBackupBalance" + i);
            rangeToRHS.put(backupWIPBalanceC[i], master.linearIntExpr(0));


            // FG Backup balance
            backupFGBalanceC[i] =
                    sub.addEq(sub.sum(backupFGStock[i - 1], sub.negative(backupFGTransfer[i]), sub.negative(backupFGStock[i])), 0,
                            "FGBackupBalance" + i);
            rangeToRHS.put(backupFGBalanceC[i], master.linearIntExpr(0));

        }
    }

    private void buildNoTransferIfNoDisruption() throws IloException {
        for (int i = 0; i < WEEKS_PER_YEAR; i++) {
            // No Backup transfer if no disruption
            if (!scenario.isSupplierDisrupted(i)) {
                IloRange range = sub.addLe(backupWIPTransfer[i], 0.0);
                rangeToRHS.put(range, master.linearIntExpr(0));
            }
            if (!scenario.isDcDisrupted(i) && !scenario.isPlantDisrupted(i)) {
                IloRange range = sub.addLe(backupFGTransfer[i], 0.0);
                rangeToRHS.put(range, master.linearIntExpr(0));
            }
        }
    }

    private void buildDesiredItemFillRatio() throws IloException {
        double totalDemand = 0.0;
        for (double demand : scenario.getRandomDemand()) {
            totalDemand += demand;
        }
        IloRange range = sub.addGe(sub.sum(desiredIFRSlack, sub.sum(fgToCustomer)), DESIRED_IFR * totalDemand);
        rangeToRHS.put(range, sub.linearNumExpr(Data.DESIRED_IFR * totalDemand));
    }

    private String[] buildNames(String string, int elements) {
        String[] names = new String[elements];
        for (int i = 0; i < names.length; i++) {
            names[i] = string.concat(String.valueOf(i));
        }
        return names;
    }

    public void updateRHS(double suggestedWIPInv, double suggestedFGInv, double backupSupplierCapacity, double[] supplierDelays,
            double backupPlantCapacity,
            double[] plantDelays, double backupDCCapacity, double[] dcDelays) throws IloException {
        // which backup policy does the proposed master solution suggest?


        // WIP Stock Policy
        backupWIPBalanceC[0].setUB(Math.max(0.0, suggestedWIPInv));

        // FG Stock Policy
        backupFGBalanceC[0].setUB(Math.max(0.0, suggestedFGInv));

        // Backup supplier policy
        updateOptionRHS(backupSupplierBalance, backupSupplierCapacity, supplierDelays, scenario.getSupplierDisruptionStart());

        // Backup Plant Policy Update
        updateOptionRHS(backupPlantBalance, backupPlantCapacity, plantDelays, scenario.getPlantDisruptionStart());

        // Backup DC Policy Update
        updateOptionRHS(backupDCBalance, backupDCCapacity, dcDelays, scenario.getDcDisruptionStart());
    }

    private void updateOptionRHS(IloRange[] backupConstraints, double rhsValue, double[] delays, int disruptionStart)
            throws IloException {

        assert rhsValue > -1e-6 : "No puede haber RHS negativos";

        for (int i = 0; i < backupConstraints.length; i++) {
            // Check rhs > 0 because due to numerical stabilities it can be e-14 and fall below the constraint initial LB
            // in which case cplex throws an error
            if (!rangeToRHS.get(backupConstraints[i]).equals(master.linearIntExpr(0))) {
                double adjusted =
                        (i >= disruptionStart && i < disruptionStart + Data.MAX_DELAY) ? rhsValue - delays[i - disruptionStart] : rhsValue;
                backupConstraints[i].setUB(Math.max(0.0, adjusted));
            }

        }
    }

    public IloCplex.Status solve() throws IloException {
        sub.solve();

        //        sub.exportModel("Subproblem.sav");
        //        sub.exportModel("Subproblem.lp");
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

    // -------- GETTERS TO INTERFACE WITH MASTER

    public int getNbConstriants() {
        return sub.getNrows();
    }

    public Collection<IloRange> getConstraints() {
        return rangeToRHS.keySet();
    }

    public IloNumExpr getRHS(IloConstraint range) throws IloException {
        return rangeToRHS.get(range);
    }

    public double getDual(IloRange range) throws IloException {
        return sub.getDual(range);
    }

    public double getObjValue() throws IloException {
        return sub.getObjValue();
    }

    public void dualFarkas(IloConstraint[] constraints, double[] coefficients) throws IloException {
        sub.dualFarkas(constraints, coefficients);
    }

    public RandomScenario getScenario() {
        return scenario;
    }


    @Override
    public Status call() throws Exception {
        buildSubProblem();
        return solve();
    }


    public void end() {
        sub.end();
    }
}
