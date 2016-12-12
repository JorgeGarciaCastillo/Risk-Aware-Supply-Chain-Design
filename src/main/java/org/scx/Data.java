package org.scx;

/**
 * Data from the SCREAM Game and aditional Risk measure date
 */
public class Data {

    // Item cost and value
    public static final int RAW_MATERIAL_COST = 50;
    public static final int WIP_COST = 80;
    public static final int FG_COST = 100;
    public static final int FG_PRICE = 225;
    public static final double IRR = .25;
    public static final double DESIRED_IFR = .99;

    // Capacities
    public static final int SUPPLIER_CAPACITY = 150;
    public static final int PLANT_CAPACITY = 150;
    public static final int BASE_DC_STOCK_LEVEL = 124;

    // Demand
    public static final int MEAN_DEMAND = 100;
    public static final int STD_DEV = 10;

    // Auxiliar constants
    public static final int WEEKS_PER_YEAR = 52;
    public static final int NUM_BACKUP_POLICIES = 7;
    public static final int MAX_DELAY = 6; // Hardwired constant to ease modeling
    public static final int NB_FACILITIES = 3;

    // Risk values
    public static final double RISK_AVERSION_FACTOR = 1e-3;
    public static final int VARIAB_IDX_PEANLTY = 2000;
    public static final int COST_TARGET = 125000;
    public static final int PROB_FINANCIAL_RISK = 150000;
    public static final int DOWNSIDE_RISK_PENALTY = 10 * FG_PRICE;
    // @see “Natural Disasters, Casualties and Power Laws: A Comparative Analysis with Armed Conflict” (2006) - Oscar Becerrar e.t. al.
    public static final double DISASTER_CASUALTY_POWER_LAW_EXPONENT = 2.06;
    public static final int MAX_DISRUPTION = 36;


    public static final int NO_DELAY_OPTION = 0;
    public static final int[] ONE_WEEK_DELAY_OPTION = new int[] {3, 6};
    public static final int[] TWO_WEEK_DELAY_OPTION = new int[] {2, 5};
    public static final int FOUR_WEEKS_DELAY_OPTION = 1;
    public static final int SIX_WEEKS_DELAY_OPTION = 4;

    // Backup options
    public static final BackupOption[] SUPPLIER_OPTIONS = new BackupOption[] {
            new BackupOption("Supplier", 0, 0, 52, 0),
            new BackupOption("Supplier", 1, 0.5, 4, 400),
            new BackupOption("Supplier", 2, 0.5, 2, 1000),
            new BackupOption("Supplier", 3, 0.5, 1, 2400),
            new BackupOption("Supplier", 4, 1.0, 6, 1000),
            new BackupOption("Supplier", 5, 1.0, 2, 3500),
            new BackupOption("Supplier", 6, 1.0, 1, 10000),
    };

    public static final BackupOption[] PLANT_OPTIONS = new BackupOption[] {
            new BackupOption("Plant", 0, 0, 52, 0),
            new BackupOption("Plant", 1, 0.5, 4, 800),
            new BackupOption("Plant", 2, 0.5, 2, 1800),
            new BackupOption("Plant", 3, 0.5, 1, 4000),
            new BackupOption("Plant", 4, 1.0, 6, 1000),
            new BackupOption("Plant", 5, 1.0, 2, 5000),
            new BackupOption("Plant", 6, 1.0, 1, 12000),
    };

    public static final BackupOption[] DC_OPTIONS = new BackupOption[] {
            new BackupOption("DC", 0, 0, 52, 0),
            new BackupOption("DC", 1, 0.5, 4, 1000),
            new BackupOption("DC", 2, 0.5, 2, 2500),
            new BackupOption("DC", 3, 0.5, 1, 6000),
            new BackupOption("DC", 4, 1.0, 6, 1500),
            new BackupOption("DC", 5, 1.0, 2, 6000),
            new BackupOption("DC", 6, 1.0, 1, 15000),
    };


    public static final class BackupOption {

        private final String name;
        private final int option;
        private final double capacity;
        private final int responseTimes;
        private final double cost;

        public BackupOption(String name, int option, double capacity, int responseTimes, double cost) {
            this.name = name;
            this.option = option;
            this.capacity = capacity;
            this.responseTimes = responseTimes;
            this.cost = cost;
        }

        public int getIndex() {
            return option;
        }

        public double getCapacity() {
            return capacity;
        }

        public int getResponseTimes() {
            return responseTimes;
        }

        public double getCost() {
            return cost;
        }

        @Override
        public String toString() {
            return name + String.valueOf(option);
        }
    }
}
