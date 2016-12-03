package org.scx.model;

import static org.scx.Data.BASE_DC_STOCK_LEVEL;
import static org.scx.Data.DESIRED_IFR;
import static org.scx.Data.MAX_DELAY;
import static org.scx.Data.PLANT_CAPACITY;
import static org.scx.Data.SUPPLIER_CAPACITY;
import static org.scx.Data.WEEKS_PER_YEAR;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import org.scx.Data;
import org.scx.Solution.BackupPoliciyData;
import org.scx.sample.RandomScenario;

import ilog.concert.IloConstraint;
import ilog.concert.IloException;
import ilog.concert.IloNumExpr;
import ilog.concert.IloNumVar;
import ilog.concert.IloRange;
import ilog.cplex.IloCplex;

/**
 * Subproblem for each sample scenario linked to a master problem
 */
public class MasterLinkedSubproblem extends ScenarioProblem implements Callable<IloCplex.Status> {

    private final IloCplex master;

    /*
     * MASTER LINK VARIABLES
     */

    private IloNumVar backupFGPolicy;
    private IloNumVar backupWIPPolicy;
    private IloNumVar backupSupplierCapacity;
    private IloNumVar backupPlantCapacity;
    private IloNumVar backupDCCapacity;

    private IloNumVar[] backupSupplierDelayedCapacity;
    private IloNumVar[] backupPlantDelayedCapacity;
    private IloNumVar[] backupDCDelayedCapacity;


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


    /**
     * Constructs SubProblem for Scenario with a reference to a master
     * 
     * @param masterProblem
     *        TODO define clear interface between master and subproblem
     * @param policyData
     *        TODO
     * @param s
     * @throws IloException
     */
    public MasterLinkedSubproblem(MulticutLShaped masterProblem, BackupPoliciyData policyData, RandomScenario s) {
        super(policyData, s);
        try {
            master = masterProblem.master;
            rangeToRHS = new LinkedHashMap<>();

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



    @Override
    protected void buildSupplyStatus() throws IloException {
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

    @Override
    protected void buildAvailability() throws IloException {
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

    @Override
    protected void buildDemand() throws IloException {
        // Demand Constraints -- record the constraints for use later
        cDemand = new IloRange[Data.WEEKS_PER_YEAR];
        for (int i = 0; i < Data.WEEKS_PER_YEAR; i++) {
            cDemand[i] = sub.addGe(sub.sum(fgToCustomer[i], lostSales[i]), scenario.getRandomDemand()[i], "Demand" + i);
            rangeToRHS.put(cDemand[i], randomDemand[i]);
        }
    }

    @Override
    protected void buildStockBalance() throws IloException {
        // Stock Balance constraints
        fgStockC = new IloRange[WEEKS_PER_YEAR];
        for (int i = 0; i < Data.WEEKS_PER_YEAR; i++) {
            fgStockC[i] = sub.addLe(fgStock[i], Data.BASE_DC_STOCK_LEVEL, "initialFgSafety_" + i);
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

    @Override
    protected void buildNoTransferIfNoDisruption() throws IloException {
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

    @Override
    protected void buildDesiredItemFillRatio() throws IloException {
        double totalDemand = 0.0;
        for (double demand : scenario.getRandomDemand()) {
            totalDemand += demand;
        }
        IloRange range = sub.addGe(sub.sum(desiredIFRSlack, sub.sum(fgToCustomer)), DESIRED_IFR * totalDemand);
        rangeToRHS.put(range, sub.linearNumExpr(Data.DESIRED_IFR * totalDemand));
    }

    /**
     * Which backup policy does the proposed master solution suggest?
     */
    public void updateRHS(BackupPoliciyData policyData) throws IloException {
        // WIP Stock Policy
        backupWIPBalanceC[0].setBounds(Math.max(0.0, policyData.backupWIPPolicy), Math.max(0.0, policyData.backupWIPPolicy));

        // FG Stock Policy
        backupFGBalanceC[0].setBounds(Math.max(0.0, policyData.backupFGPolicy), Math.max(0.0, policyData.backupFGPolicy));

        // Backup supplier policy
        updateOptionRHS(backupSupplierBalance, policyData.backupSupplierCapacity, policyData.backupSupplierDelayedCapacity,
                scenario.getSupplierDisruptionStart());

        // Backup Plant Policy Update
        updateOptionRHS(backupPlantBalance, policyData.backupPlantCapacity, policyData.backupPlantDelayedCapacity,
                scenario.getPlantDisruptionStart());

        // Backup DC Policy Update
        updateOptionRHS(backupDCBalance, policyData.backupDCCapacity, policyData.backupDCDelayedCapacity,
                scenario.getDcDisruptionStart());
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

    public void export() {
        try {
            sub.exportModel("Subproblem.lp");
        } catch (IloException e) {
            e.printStackTrace();
        }

    }
}
