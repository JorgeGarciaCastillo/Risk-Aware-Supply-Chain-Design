# Supply Chain Resilience Evaluation and Mitigation #

### What is this repository for? ###

The code here provides a solution method to handle the SCREAM game as a stochastic program in a global way.

The code here implements the algorithm Sampled Average Approximation solved by MulticutLShaped method using Java API to CPLEX. The model is a robust prduction planning problem.
It assumes that demand is independent for disruptions and disruptions start is uniformly distributed for the weeks of the year.

The code actually contains different approaches:

* Single deterministic full MIP model : Solves the robust production planning problem for 1 scenario
* Decomposed multiscenario MIP model : Solves the robust production planning problem for a set of fixed scenarios
* Sampled Average Approximation model : Solves the robust production planning problem for the whole distribution of demand and disruptions

To run each option you need to change the following field in the pom.xml file:
```xml
<arguments>
  <argument>discrete</argument>
</arguments>
```
The different values are listed in the Scream.java class.

### CPLEX ###
The current version works with CPLEX 12.6 and *should* work with earlier versions. 
There is a reference to cplex.jar and cplex.dll in the pom.xml but due to licesing constraints I am unable to distribute it.

### Setup ###

To run the code it is required:
* Java 8+
* Maven 3.1.1+
* Install CPLEX Studio.
* Download the code from the repository
* To run the code from a command line, open a terminal/DOS window, change to the directory where you parked the code, and execute the following:

```
#!DOS

mvn clean install
mvn exec:java
```
