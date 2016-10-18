package org.scx.model;

import java.util.Objects;

import ilog.cplex.IloCplex;


/**
 * This is a container class to hold solutions to the problem.
 */
public class Solution {

    public double totalCost;
    public double negativeRevenue;
    public double avgLostSales;
    public double facBackupCost;
    public double invCarryCost;

    public BackupPolicy backupPolicy;

    public double[] supplierProd;
    public double[] plantProd;
    public double[] dcTransfer;

    public double[] wip;
    public double[] fgToDC;
    public double[] fgToCustomer;

    public double[] fgStock;
    public double[] lostSales;

    public double[] backupFGTransfer;
    public double[] backupWIPTransfer;

    public double[] backupSupplier;
    public double[] backupWIP;
    public double[] backupFG;
    public double[] backupPlant;
    public double[] backupDC;

    public IloCplex.CplexStatus status;

    public static class BackupPolicy {

        private int backupWIPInventory;
        private int backupFGInventory;

        private int backupSupplier;
        private int backupPlant;
        private int backupDC;

        public BackupPolicy(int backupSupplier, int backupWIP, int backupPlant, int backupFG, int backupDC) {
            this.backupSupplier = backupSupplier;
            this.backupWIPInventory = backupWIP;
            this.backupPlant = backupPlant;
            this.backupFGInventory = backupFG;
            this.backupDC = backupDC;
        }

        public int getBackupSupplier() {
            return backupSupplier;
        }

        public int getBackupWIP() {
            return backupWIPInventory;
        }

        public int getBackupPlant() {
            return backupPlant;
        }

        public int getBackupFG() {
            return backupFGInventory;
        }

        public int getBackupDC() {
            return backupDC;
        }

        @Override
        public int hashCode() {
            return Objects.hash(backupDC, backupFGInventory, backupPlant, backupSupplier, backupWIPInventory);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            BackupPolicy other = (BackupPolicy) obj;
            return backupDC == other.backupDC
                    && backupFGInventory == other.backupFGInventory
                    && backupWIPInventory == other.backupWIPInventory
                    && backupSupplier == other.backupSupplier
                    && backupPlant == other.backupPlant;
        }


        @Override
        public String toString() {
            return "[" + backupWIPInventory + ", "
                    + backupFGInventory + ", "
                    + backupSupplier + ", "
                    + backupPlant + ", "
                    + backupDC + "]";
        }
    }

    @Override
    public String toString() {
        return "Solution [totalCost=" + totalCost + ", avgLostSales=" + avgLostSales + ", facBackupCost=" + facBackupCost
                + ", invCarryCost=" + invCarryCost + ", backupPolicy=" + backupPolicy + "]";
    }
}
