package org.scx.model;

import static org.scx.Data.FG_COST;
import static org.scx.Data.FG_PRICE;
import static org.scx.Data.IRR;
import static org.scx.Data.MEAN_DEMAND;
import static org.scx.Data.NUM_BACKUP_POLICIES;
import static org.scx.Data.PLANT_CAPACITY;
import static org.scx.Data.SUPPLIER_CAPACITY;
import static org.scx.Data.WEEKS_PER_YEAR;
import static org.scx.Data.WIP_COST;

import org.scx.Data;
import org.scx.Data.BackupOption;
import org.scx.model.Solution.BackupPolicy;
import org.scx.sample.RandomScenario;

import ilog.concert.IloException;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumExpr;
import ilog.concert.IloNumVar;
import ilog.cplex.IloCplex;


/**
 * This implements a single MIP model to solve the robust production problem for a single scenario
 */
public class SingleModel {

    private final IloCplex cplex;

    // Cost components
    private IloNumExpr negativeRevenue;
    private IloNumExpr avgLostSales;
    private IloNumExpr facBackupCost;
    private IloNumExpr invCarryCost;

    private IloNumExpr avgWIPInv;
    private IloNumExpr avgFGInv;

    // Backup Policies 
    private IloNumVar backupFGPolicy;
    private IloNumVar backupWIPPolicy;

    private IloNumVar[] backupSupplierPolicy;
    private IloNumVar[] backupDCPolicy;
    private IloNumVar[] backupPlantPolicy;

    // Sales slack
    private IloNumVar[] lostSales;

    // Regular Facility Status
    private IloNumVar[] dcStatus;
    private IloNumVar[] plantStatus;
    private IloNumVar[] supplierStatus;

    // Production
    private IloNumVar[] supplierProd;
    private IloNumVar[] plantProd;
    private IloNumVar[] dcTransfer;

    // Inventory
    private IloNumExpr wipStock;
    private IloNumVar[] fgStock;


    // Products
    private IloNumVar[] wip;
    private IloNumVar[] fgToDC;
    private IloNumVar[] fgToCustomer;

    // Backup Production
    private IloNumVar[] backupWIPTransfer;
    private IloNumVar[] backupFGTransfer;
    private IloNumVar[] backupSupplier;
    private IloNumVar[] backupPlant;
    private IloNumVar[] backupDC;
    private IloNumVar[][] backupSupplierOption;
    private IloNumVar[][] backupPlantOption;
    private IloNumVar[][] backupDCOption;

    // Backup Stock
    private IloNumVar[] backupWIPStock;
    private IloNumVar[] backupFGStock;


    /**
     * Constructor.
     * 
     * @param data
     *        scream problem data
     * @throws IloException
     *         if CPLEX is unhappy
     */
    public SingleModel(RandomScenario scenario) throws IloException {
        cplex = new IloCplex();

        buildVariables();
        buildObjective();


        buildStatusConstraints(scenario);
        buildAvailabilityConstraints();
        buildDemandConstraints(scenario);
        buildStockAndFlowBalanceConstraints();
        buildDisruptionConstraints(scenario);

        // BackUpPolicies
        buildWIPStockPolicy(scenario);
        buildFGStockPolicy(scenario);
        buildPolicyBackupOptionsConstraints(scenario);
        buildOnePolicyConstraints();
    }

    /**
     * Creates model variables
     */
    private void buildVariables() throws IloException {
        lostSales = cplex.numVarArray(WEEKS_PER_YEAR, 0.0, Double.MAX_VALUE, buildNames("lostSales", WEEKS_PER_YEAR));

        dcStatus = cplex.boolVarArray(WEEKS_PER_YEAR, buildNames("dcStatus", WEEKS_PER_YEAR));
        plantStatus = cplex.boolVarArray(WEEKS_PER_YEAR, buildNames("plantStatus", WEEKS_PER_YEAR));
        supplierStatus = cplex.boolVarArray(WEEKS_PER_YEAR, buildNames("supplierStatus", WEEKS_PER_YEAR));

        backupFGPolicy = cplex.numVar(0.0, Double.MAX_VALUE, "backupFGPolicy");
        backupWIPPolicy = cplex.numVar(0.0, Double.MAX_VALUE, "backupWIPPolicy");

        backupSupplierPolicy = cplex.boolVarArray(NUM_BACKUP_POLICIES, buildNames("backupSupplierPolicy", NUM_BACKUP_POLICIES));
        backupPlantPolicy = cplex.boolVarArray(NUM_BACKUP_POLICIES, buildNames("backupPlantPolicy", NUM_BACKUP_POLICIES));
        backupDCPolicy = cplex.boolVarArray(NUM_BACKUP_POLICIES, buildNames("backupDCPolicy", NUM_BACKUP_POLICIES));

        supplierProd = cplex.numVarArray(WEEKS_PER_YEAR, 0.0, Double.MAX_VALUE, buildNames("supplierProd", WEEKS_PER_YEAR));
        plantProd = cplex.numVarArray(WEEKS_PER_YEAR, 0.0, Double.MAX_VALUE, buildNames("plantProd", WEEKS_PER_YEAR));
        dcTransfer = cplex.numVarArray(WEEKS_PER_YEAR, 0.0, Double.MAX_VALUE, buildNames("dcTransfer", WEEKS_PER_YEAR));

        // Max stock is base stock level
        fgStock = cplex.numVarArray(WEEKS_PER_YEAR, 0.0, Data.BASE_DC_STOCK_LEVEL, buildNames("fgStock", WEEKS_PER_YEAR));

        wip = cplex.numVarArray(WEEKS_PER_YEAR, 0.0, Double.MAX_VALUE, buildNames("wip", WEEKS_PER_YEAR));
        fgToDC = cplex.numVarArray(WEEKS_PER_YEAR, 0.0, Double.MAX_VALUE, buildNames("fgToDC", WEEKS_PER_YEAR));
        fgToCustomer = cplex.numVarArray(WEEKS_PER_YEAR, 0.0, Double.MAX_VALUE, buildNames("fgToSell", WEEKS_PER_YEAR));

        backupWIPTransfer = cplex.numVarArray(WEEKS_PER_YEAR, 0.0, Double.MAX_VALUE, buildNames("backupWIPTransfer", WEEKS_PER_YEAR));
        backupFGTransfer = cplex.numVarArray(WEEKS_PER_YEAR, 0.0, Double.MAX_VALUE, buildNames("backupFGTransfer", WEEKS_PER_YEAR));
        backupSupplier = cplex.numVarArray(WEEKS_PER_YEAR, 0.0, Double.MAX_VALUE, buildNames("backupSupplier", WEEKS_PER_YEAR));
        backupPlant = cplex.numVarArray(WEEKS_PER_YEAR, 0.0, Double.MAX_VALUE, buildNames("backupPlant", WEEKS_PER_YEAR));
        backupDC = cplex.numVarArray(WEEKS_PER_YEAR, 0.0, Double.MAX_VALUE, buildNames("backupDC", WEEKS_PER_YEAR));

        backupSupplierOption = new IloNumVar[WEEKS_PER_YEAR][];
        backupPlantOption = new IloNumVar[WEEKS_PER_YEAR][];
        backupDCOption = new IloNumVar[WEEKS_PER_YEAR][];
        for (int i = 0; i < WEEKS_PER_YEAR; i++) {
            backupSupplierOption[i] =
                    cplex.numVarArray(NUM_BACKUP_POLICIES, 0.0, Double.MAX_VALUE,
                            buildNames("backupDCOption" + i + "_", NUM_BACKUP_POLICIES));
            backupPlantOption[i] =
                    cplex.numVarArray(NUM_BACKUP_POLICIES, 0.0, Double.MAX_VALUE,
                            buildNames("backupDCOption" + i + "_", NUM_BACKUP_POLICIES));
            backupDCOption[i] =
                    cplex.numVarArray(NUM_BACKUP_POLICIES, 0.0, Double.MAX_VALUE,
                            buildNames("backupDCOption" + i + "_", NUM_BACKUP_POLICIES));
        }

        backupWIPStock = cplex.numVarArray(WEEKS_PER_YEAR, 0.0, Double.MAX_VALUE, buildNames("backupWIPStock", WEEKS_PER_YEAR));
        backupFGStock = cplex.numVarArray(WEEKS_PER_YEAR, 0.0, Double.MAX_VALUE, buildNames("backupFGStock", WEEKS_PER_YEAR));
    }

    /**
     * Creates objective to minize : TotalCost = BackUpCost + InvCarryCost + LostSales
     * 
     * @throws IloException
     */
    private void buildObjective() throws IloException {
        // BackUp Policy Cost
        IloLinearNumExpr expr = cplex.linearNumExpr();
        for (int i = 0; i < Data.NUM_BACKUP_POLICIES; i++) {
            expr.addTerm(backupDCPolicy[i], Data.DC_OPTIONS[i].getCost());
            expr.addTerm(backupPlantPolicy[i], Data.PLANT_OPTIONS[i].getCost());
            expr.addTerm(backupSupplierPolicy[i], Data.SUPPLIER_OPTIONS[i].getCost());
        }
        facBackupCost = expr;

        // Inventory accounting
        wipStock = cplex.prod(Data.SUPPLIER_CAPACITY, cplex.sum(supplierStatus));
        avgWIPInv = cplex.prod(1.0 / WEEKS_PER_YEAR, cplex.sum(wipStock, cplex.sum(backupWIPStock), cplex.sum(backupSupplier)));
        avgFGInv = cplex.prod(1.0 / WEEKS_PER_YEAR, cplex.sum(cplex.sum(fgStock), cplex.sum(backupFGStock), cplex.sum(backupDC)));

        // InvCarrtCost = InternalRateOfReturn * ((WIP_Cost * WIP_Stock) + (FG_Cost*FG_Stock))
        invCarryCost = cplex.prod(IRR, cplex.sum(cplex.prod(WIP_COST, avgWIPInv), cplex.prod(FG_COST, avgFGInv)));

        // lostSales cost is price - value
        avgLostSales = cplex.prod((FG_PRICE - FG_COST), cplex.sum(lostSales));
        negativeRevenue = cplex.prod(-FG_PRICE, cplex.sum(fgToCustomer));


        // Minimize TotalCost = BackUpCost + InvCarryCost + LostSales
        cplex.addMinimize(cplex.sum(invCarryCost, facBackupCost, avgLostSales), "TotalCost");
    }

    /**
     * Do not produce when there is a disruption
     */
    private void buildStatusConstraints(RandomScenario scenario) throws IloException {
        for (int i = 0; i < Data.WEEKS_PER_YEAR; i++) {
            // Supplier status
            cplex.addLe(supplierProd[i], cplex.prod(SUPPLIER_CAPACITY, supplierStatus[i]), "Supplier" + i);

            // Plant status
            cplex.addLe(plantProd[i], cplex.prod(PLANT_CAPACITY, plantStatus[i]), "Plant" + i);

            // DC status
            cplex.addLe(dcTransfer[i], cplex.prod(scenario.getRandomDemand()[i], dcStatus[i]), "DC" + i);
        }
    }

    /**
     * Production of each facility must be equal to the inflow to that facility
     */
    private void buildAvailabilityConstraints() throws IloException {
        // Availability constraints
        for (int i = 0; i < Data.WEEKS_PER_YEAR; i++) {
            // WIP = supplierProduction + backupSupplier + backupWIPused
            cplex.addEq(wip[i], cplex.sum(supplierProd[i], backupSupplier[i], backupWIPTransfer[i]), "WIP" + i);

            // FG to DC  = plantProduction + backupPlant
            cplex.addEq(fgToDC[i], cplex.sum(plantProd[i], backupPlant[i]), "FGtoDC" + i);

            // FG to Customer = Goods from DC + backupFGused + backupDC
            cplex.addEq(fgToCustomer[i], cplex.sum(dcTransfer[i], backupFGTransfer[i], backupDC[i]), "FGtoCustomer" + i);
        }
    }

    /**
     * Production plus slack from lost sales must cover demand
     */
    private void buildDemandConstraints(RandomScenario scenario) throws IloException {
        // Demand Constraints
        int[] randomDemand = scenario.getRandomDemand();
        for (int i = 0; i < Data.WEEKS_PER_YEAR; i++) {
            cplex.addGe(cplex.sum(fgToCustomer[i], lostSales[i]), randomDemand[i], "Demand" + i);
        }
    }

    /**
     * Time flow balance of goods and stocks
     */
    private void buildStockAndFlowBalanceConstraints() throws IloException {
        // Stock Balance constraints
        cplex.addLe(fgStock[0], Data.BASE_DC_STOCK_LEVEL, "InitialFGStock");

        cplex.addEq(plantProd[0], wip[0], "WIPStockBalance" + 0);
        cplex.addEq(dcTransfer[0], cplex.sum(fgToDC[0], cplex.negative(fgStock[0])), "FGStockBalance" + 0);

        for (int i = 1; i < Data.WEEKS_PER_YEAR; i++) {
            // PlantProduction = WIP
            cplex.addEq(plantProd[i], wip[i], "WIPStockBalance" + i);

            // DCtransfer = FGreceived + difference in stock
            cplex.addEq(dcTransfer[i], cplex.sum(fgToDC[i], fgStock[i - 1], cplex.negative(fgStock[i])), "FGStockBalance" + i);

            // BackupWIPStock balance
            cplex.addEq(backupWIPStock[i], cplex.sum(backupWIPStock[i - 1], cplex.negative(backupWIPTransfer[i])), "WIPBackupBalance" + i);

            // BackupFGStock balance
            cplex.addEq(backupFGStock[i], cplex.sum(backupFGStock[i - 1], cplex.negative(backupFGTransfer[i])), "FGBackupBalance" + i);
        }
    }

    /**
     * Status = 1 if facility is available, 0 if there is a disruption
     */
    private void buildDisruptionConstraints(RandomScenario scenario) throws IloException {
        // Disruption constraints
        for (int i = 0; i < Data.WEEKS_PER_YEAR; i++) {
            cplex.addEq(dcStatus[i], (i >= scenario.getDcDisruptionStart() && i < scenario.getDcDisruptionEnd()) ? 0 : 1, "DCStatus" + i);
            cplex.addEq(plantStatus[i], (i >= scenario.getPlantDisruptionStart() && i < scenario.getPlantDisruptionEnd()) ? 0 : 1,
                    "PlantStatus" + i);
            cplex.addEq(supplierStatus[i], (i >= scenario.getSupplierDisruptionStart() && i < scenario.getSupplierDisruptionEnd()) ? 0 : 1,
                    "SupplierStatus" + i);
        }
    }

    /**
     * Initialize WIPStock Policy and avoid transfer if there is no disruption
     */
    private void buildWIPStockPolicy(RandomScenario scenario) throws IloException {
        // WIP Stock Policy
        cplex.addEq(backupWIPStock[0], backupWIPPolicy, "BackupWIPStock");
        for (int i = 0; i < Data.WEEKS_PER_YEAR; i++) {
            // No Backup WIP outside disruption
            if (i < scenario.getSupplierDisruptionStart() || i >= scenario.getSupplierDisruptionEnd()) {
                cplex.addEq(backupWIPTransfer[i], 0.0, "NoWIPBackup" + i);
            }
        }
    }

    /**
     * Initialize FGStock Policy and avoid transfer if there is no disruption
     */
    private void buildFGStockPolicy(RandomScenario scenario) throws IloException {
        // FG Stock Policy
        cplex.addEq(backupFGStock[0], backupFGPolicy, "BackupFGStock");
        for (int i = 0; i < Data.WEEKS_PER_YEAR; i++) {
            // No Backup FG if DC and Plant are active
            if (!scenario.isDcDisrupted(i) && !scenario.isPlantDisrupted(i)) {
                cplex.addEq(backupFGTransfer[i], 0.0, "NoFGBackup" + i);
            }
        }
    }

    /**
     * Link the diferent backup policies of each facility with the capacity covered
     */
    private void buildPolicyBackupOptionsConstraints(RandomScenario scenario) throws IloException {
        // Supplier Backup Options
        for (BackupOption option : Data.SUPPLIER_OPTIONS) {
            IloNumVar backUpActiveVar = backupSupplierPolicy[option.getIndex()];
            for (int i = 0; i < Data.WEEKS_PER_YEAR; i++) {
                if (i < scenario.getDcDisruptionStart() + option.getResponseTimes() || i >= scenario.getDcDisruptionEnd()) {
                    cplex.addEq(backupSupplierOption[i][option.getIndex()], 0.0, "NoSupplierBackup" + option.getIndex() + "_" + i);
                } else {
                    int j = i;
                    for (; j < scenario.getDcDisruptionEnd(); j++) {
                        String name = "ActivableSupplierBackup" + option.getIndex() + "_" + j;
                        cplex.addEq(backupSupplierOption[j][option.getIndex()],
                                cplex.prod(SUPPLIER_CAPACITY * option.getCapacity(), backUpActiveVar), name);
                    }
                    i = j - 1;
                }
            }
        }

        // Plant Backup Options
        for (BackupOption option : Data.PLANT_OPTIONS) {
            IloNumVar backUpActiveVar = backupPlantPolicy[option.getIndex()];
            for (int i = 0; i < Data.WEEKS_PER_YEAR; i++) {
                if (i < scenario.getDcDisruptionStart() + option.getResponseTimes() || i >= scenario.getDcDisruptionEnd()) {
                    cplex.addEq(backupPlantOption[i][option.getIndex()], 0.0, "NoPlantBackup" + option.getIndex() + "_" + i);
                } else {
                    int j = i;
                    for (; j < scenario.getDcDisruptionEnd(); j++) {
                        String name = "ActivablePlantBackup" + option.getIndex() + "_" + j;
                        cplex.addEq(backupPlantOption[j][option.getIndex()],
                                cplex.prod(PLANT_CAPACITY * option.getCapacity(), backUpActiveVar), name);
                    }
                    i = j - 1;
                }
            }
        }

        // DC Backup Options
        for (BackupOption option : Data.DC_OPTIONS) {
            IloNumVar backUpActiveVar = backupDCPolicy[option.getIndex()];
            for (int i = 0; i < Data.WEEKS_PER_YEAR; i++) {
                if (i < scenario.getDcDisruptionStart() + option.getResponseTimes() || i >= scenario.getDcDisruptionEnd()) {
                    cplex.addEq(backupDCOption[i][option.getIndex()], 0.0, "NoDCBackup" + option.getIndex() + "_" + i);
                } else {
                    int j = i;
                    for (; j < scenario.getDcDisruptionEnd(); j++) {
                        String name = "ActivableDCBackup" + option.getIndex() + "_" + j;
                        cplex.addLe(backupDCOption[j][option.getIndex()], cplex.prod(MEAN_DEMAND * option.getCapacity(), backUpActiveVar),
                                name);
                    }
                    i = j - 1;
                }
            }
        }
    }

    /**
     * Only one policy option per facility must be chosen
     */
    private void buildOnePolicyConstraints() throws IloException {
        // Backup just one option
        for (int i = 0; i < Data.WEEKS_PER_YEAR; i++) {
            cplex.addEq(backupSupplier[i], cplex.sum(backupSupplierOption[i]), "backupSupplierBalance" + i);
            cplex.addEq(backupPlant[i], cplex.sum(backupPlantOption[i]), "backupPlantBalance" + i);
            cplex.addEq(backupDC[i], cplex.sum(backupDCOption[i]), "backupDCBalance" + i);
        }

        cplex.addEq(cplex.sum(backupSupplierPolicy), 1.0, "OneSupplierBackup");
        cplex.addEq(cplex.sum(backupPlantPolicy), 1.0, "OnePlantBackup");
        cplex.addEq(cplex.sum(backupDCPolicy), 1.0, "OneDCBackup");
    }

    /**
     * Solves the unified model.
     * 
     * @return the solution (in an instance of Solution)
     * @throws IloException
     *         if CPLEX encounters problems
     */
    public Solution solve() throws IloException {
        Solution s = new Solution();
        cplex.exportModel("ScreamSingleModel.lp");
        cplex.exportModel("ScreamSingleModel.sav");
        cplex.writeParam("ScreamSingleModel.prm");

        if (cplex.solve()) {
            s.totalCost = cplex.getObjValue();
            s.negativeRevenue = cplex.getValue(negativeRevenue);
            s.avgLostSales = cplex.getValue(avgLostSales);
            s.facBackupCost = cplex.getValue(facBackupCost);
            s.invCarryCost = cplex.getValue(invCarryCost);

            s.supplierProd = cplex.getValues(supplierProd);
            s.plantProd = cplex.getValues(plantProd);
            s.dcTransfer = cplex.getValues(dcTransfer);

            s.wip = cplex.getValues(wip);
            s.fgToDC = cplex.getValues(fgToDC);
            s.fgToCustomer = cplex.getValues(fgToCustomer);

            s.fgStock = cplex.getValues(fgStock);
            s.lostSales = cplex.getValues(lostSales);

            s.backupSupplier = cplex.getValues(backupSupplier);
            s.backupPlant = cplex.getValues(backupPlant);
            s.backupDC = cplex.getValues(backupDC);

            s.backupWIP = cplex.getValues(backupWIPStock);
            s.backupFG = cplex.getValues(backupFGStock);

            s.backupWIPTransfer = cplex.getValues(backupWIPTransfer);
            s.backupFGTransfer = cplex.getValues(backupFGTransfer);


            BackupOption supplierOption = Data.SUPPLIER_OPTIONS[0];
            BackupOption plantOption = Data.PLANT_OPTIONS[0];
            BackupOption dcOption = Data.DC_OPTIONS[0];
            for (int i = 0; i < Data.NUM_BACKUP_POLICIES; i++) {
                if (cplex.getValue(backupSupplierPolicy[i]) > 0.5) {
                    supplierOption = Data.SUPPLIER_OPTIONS[i];
                }

                if (cplex.getValue(backupPlantPolicy[i]) > 0.5) {
                    plantOption = Data.PLANT_OPTIONS[i];
                }
                if (cplex.getValue(backupDCPolicy[i]) > 0.5) {
                    dcOption = Data.DC_OPTIONS[i];
                }
            }

            s.backupPolicy = new BackupPolicy(supplierOption.getIndex(),
                    (int) cplex.getValue(backupWIPPolicy),
                    plantOption.getIndex(),
                    (int) cplex.getValue(backupFGPolicy),
                    dcOption.getIndex());

        }
        s.status = cplex.getCplexStatus();
        return s;
    }


    private String[] buildNames(String string, int elements) {
        String[] names = new String[elements];
        for (int i = 0; i < names.length; i++) {
            names[i] = string.concat(String.valueOf(i));
        }
        return names;
    }
}
