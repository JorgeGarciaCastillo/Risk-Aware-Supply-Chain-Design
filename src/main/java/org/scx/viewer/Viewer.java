package org.scx.viewer;


import java.util.Random;

import org.graphstream.graph.Edge;
import org.graphstream.graph.Graph;
import org.graphstream.graph.Node;
import org.graphstream.graph.implementations.MultiGraph;
import org.scx.Data;
import org.scx.Solution;
import org.scx.model.SingleModel;
import org.scx.sample.MonteCarloSampler;
import org.scx.sample.RandomScenario;
import org.scx.sample.SingleScenarioSampler;

/**
 * Graphic viewer of SCREAM solution
 */
public class Viewer {

    private static final String SCREAM = "SCREAM";

    private Graph graph;

    private Solution solution;
    private final RandomScenario scenario;

    /**
     * Creates a graphic visualization for a random scenario
     * 
     * @param solution
     *        Solution to visualize
     * @param scenario
     *        Scenario to visualize
     */
    public Viewer(Solution solution, RandomScenario scenario) {
        this.solution = solution;
        this.scenario = scenario;

        graph = creteNetworkFlowGraphGraph(solution);
        graph.addAttribute("ui.quality");
        graph.addAttribute("ui.antialias");
        String styleSheet =
                "node {\n" +
                        "size-mode: dyn-size;\n" +
                        "shape: box; \n" +
                        "size: 20px;\n" +
                        "fill-mode: dyn-plain;\n" +
                        "fill-color:  green;\n" +
                        "text-mode: normal;\n" +
                        "z-index: 1;\n" +
                        "}\n" +
                        "node#Time {\n" +
                        "size-mode: dyn-size;\n" +
                        "shape: box; \n" +
                        "size: 40px;\n" +
                        "fill-color:  gray;\n" +
                        "text-mode: normal;\n" +
                        "}\n" +
                        "node#Customer {\n" +
                        "size-mode: dyn-size;\n" +
                        "shape: box; \n" +
                        "size: 30px;\n" +
                        "fill-color:  green;\n" +
                        "text-mode: normal;\n" +
                        "}\n" +
                        "edge {\n" +
                        "shape: line;\n" +
                        "fill-mode: dyn-plain;\n" +
                        "fill-color:  black, red, blue, yellow, purple;\n" +
                        "z-index: 0;\n" +
                        "}";

        graph.addAttribute("ui.stylesheet", styleSheet);

    }

    private final Graph creteNetworkFlowGraphGraph(Solution solutionToGraph) {
        Graph graph = new MultiGraph(SCREAM);

        String[] nodes =
                new String[] {"Supplier", "Plant", "DC", "SafetyWIP", "SafetyFG", "BackupSupplier", "BackupPlant", "BackupDC", "BackupWIP",
                        "BackupFG", "Customer"};

        // Aux time node
        Node time = graph.addNode("Time");
        time.setAttribute("node", time);
        time.setAttribute("ui.label", "Week 0");
        time.setAttribute("Week", 0);
        time.setAttribute("x", -0.5);
        time.setAttribute("y", 0);

        // Set up network nodes
        for (String nodeName : nodes) {
            Node node = graph.addNode(nodeName);
            node.setAttribute("node", node);
            node.setAttribute("ui.label", nodeName);
        }

        Node supplier = graph.getNode("Supplier");
        supplier.setAttribute("x", 0);
        supplier.setAttribute("y", 0);

        Node plant = graph.getNode("Plant");
        plant.setAttribute("x", 2);
        plant.setAttribute("y", 0);

        Node dc = graph.getNode("DC");
        dc.setAttribute("x", 4);
        dc.setAttribute("y", 0);

        Node customer = graph.getNode("Customer");
        customer.setAttribute("x", 6);
        customer.setAttribute("y", 0);


        // Plant inbound flows
        graph.addEdge("SupplierProd", "Supplier", "Plant", true);
        graph.addEdge("SafetyWIPFlow", "SafetyWIP", "Plant", true);
        graph.addEdge("BackupWIPFlow", "BackupWIP", "Plant", true);
        graph.addEdge("BackupSupplierFlow", "BackupSupplier", "Plant", true);

        // DC inbound flows
        graph.addEdge("PlantProd", "Plant", "DC", true);
        graph.addEdge("SafetyFGFlow", "SafetyFG", "DC", true);
        graph.addEdge("BackupPlantFlow", "BackupPlant", "DC", true);

        // Customer inbound flows
        graph.addEdge("DCProd", "DC", "Customer", true);
        graph.addEdge("BackupFGFlow", "BackupFG", "Customer", true);
        graph.addEdge("BackupDCFlow", "BackupDC", "Customer", true);

        return graph;

    }

    public void display() throws InterruptedException {
        graph.display();
        showTimedEvents();
    }

    private void showTimedEvents() throws InterruptedException {
        for (int i = 0; i < Data.WEEKS_PER_YEAR; i++) {
            Thread.sleep(500L);
            updateTime(i);
            addDisruptions(i);
            addBackupStyles(i);
            addEdgeFlows(i);
            addLostSales(i);
        }
    }

    private void updateTime(int i) {
        Node node = graph.getNode("Time");
        node.changeAttribute("Week", i);
        node.changeAttribute("ui.label", "Week " + node.getNumber("Week"));
    }

    private void addDisruptions(int i) {
        Node node = graph.getNode("Supplier");
        String color = scenario.isSupplierDisrupted(i) ? "red" : "green";
        node.addAttribute("ui.style", "fill-color: " + color + ";");

        node = graph.getNode("Plant");
        color = scenario.isPlantDisrupted(i) ? "red" : "green";
        node.addAttribute("ui.style", "fill-color: " + color + ";");

        node = graph.getNode("DC");
        color = scenario.isDcDisrupted(i) ? "red" : "green";
        node.addAttribute("ui.style", "fill-color: " + color + ";");
    }

    private void addBackupStyles(int i) {
        Node node = graph.getNode("BackupSupplier");
        String color = (scenario.isSupplierDisrupted(i) && solution.backupPolicy.getBackupSupplier() > 0) ? "green" : "red";
        node.addAttribute("ui.style", "fill-color: " + color + ";");

        node = graph.getNode("BackupPlant");
        color = (scenario.isPlantDisrupted(i) && solution.backupPolicy.getBackupPlant() > 0) ? "green" : "red";
        node.addAttribute("ui.style", "fill-color: " + color + ";");

        node = graph.getNode("BackupDC");
        color = (scenario.isDcDisrupted(i) && solution.backupPolicy.getBackupDC() > 0) ? "green" : "red";
        node.addAttribute("ui.style", "fill-color: " + color + ";");

    }

    private void addEdgeFlows(int i) {
        Edge edge = graph.getEdge("SupplierProd");
        edge.addAttribute("ui.label", solution.supplierProd[i]);

        edge = graph.getEdge("SafetyWIPFlow");
        edge.addAttribute("ui.label", solution.wip[i]);

        edge = graph.getEdge("BackupWIPFlow");
        edge.addAttribute("ui.label", solution.backupWIPTransfer[i]);

        edge = graph.getEdge("BackupSupplierFlow");
        edge.addAttribute("ui.label", solution.backupSupplier[i]);

        edge = graph.getEdge("PlantProd");
        edge.addAttribute("ui.label", solution.plantProd[i]);

        edge = graph.getEdge("SafetyFGFlow");
        edge.addAttribute("ui.label", solution.fgStock[i]);

        edge = graph.getEdge("BackupPlantFlow");
        edge.addAttribute("ui.label", solution.backupPlant[i]);

        edge = graph.getEdge("DCProd");
        edge.addAttribute("ui.label", solution.dcTransfer[i]);

        edge = graph.getEdge("BackupFGFlow");
        edge.addAttribute("ui.label", solution.backupFGTransfer[i]);

        edge = graph.getEdge("BackupDCFlow");
        edge.addAttribute("ui.label", solution.backupDC[i]);
    }

    private void addLostSales(int i) {
        Node customer = graph.getNode("Customer");
        String color = solution.lostSales[i] > 0.0 ? "red" : "green";
        customer.addAttribute("ui.style", "fill-color: " + color + ";");
    }

    public Solution getInstance() {
        return this.solution;
    }

    public Graph getNetworkGraph() {
        return graph;
    }

    public static void main(String[] args) throws Exception {
        RandomScenario scenario = new SingleScenarioSampler().generate(1, 1).get(0);
        SingleModel model = new SingleModel(scenario);
        Solution solution = model.solve();

        Viewer viewer = new Viewer(solution, new MonteCarloSampler(new Random(0)).generate(1, 1).get(0));
        viewer.display();
    }


}
