/*
 * Copyright (c) 2014 by John Collins
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.powertac.customer.coldstorage;

import java.util.List;

import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.random.JDKRandomGenerator;
import org.apache.commons.math3.random.RandomGenerator;
import org.apache.log4j.Logger;
import org.powertac.common.RandomSeed;
import org.powertac.common.RegulationCapacity;
import org.powertac.common.Tariff;
import org.powertac.common.TariffEvaluator;
import org.powertac.common.TariffSubscription;
import org.powertac.common.WeatherReport;
import org.powertac.common.config.ConfigurableInstance;
import org.powertac.common.config.ConfigurableValue;
import org.powertac.common.enumerations.PowerType;
import org.powertac.common.state.Domain;
import org.powertac.common.state.StateChange;
import org.powertac.customer.AbstractCustomer;

/**
 * Model of a cold-storage warehouse with multiple refrigeration units.
 * The size of the refrigeration units is specified as stockCapacity. The
 * number is indeterminate - as many as needed will be used, depending on
 * heat loss and current internal temperature. If currentTemp < nominalTemp and
 * falling or steady, then a unit will be de-energized. If currentTemp >=
 * nominalTemp and rising or steady, then another unit will be activated. 
 * 
 * @author John Collins
 */
@Domain
@ConfigurableInstance
public class ColdStorage extends AbstractCustomer
{
  static private Logger log = Logger.getLogger(ColdStorage.class.getName());

  // handy contstants
  static final double R_CONVERSION = 3.1545 / 1000.0; // kW/m^2-K
  static final double TON_CONVERSION = 3.504; // kW heat
  static final double CP_ICE = 0.564; // kWh/tonne-K
  static final double GROUND_TEMP = 3.0; // don't freeze the ground

  // model parameters
  private double minTemp = -35.0; // deg C
  private double maxTemp = -10.0;
  private double nominalTemp = -20.0;

  private double roofArea = 900.0; //m^2
  private double roofRValue = 40.0;
  private double wallArea = 1440.0; //m^2
  private double wallRValue = 22.0;
  private double floorRValue = 15.0; // area same as roof
  private double infiltrationRatio = 0.5; // added to (wall + roof) loss

  private double cop = 1.5; // coefficient of performance
  private double stockCapacity = 500.0; // tonnes of water ice
  private double turnoverRatio = 0.1; // new stock/day
  private double turnoverSd = 0.015; // sd of turnover
  private double newStockTemp = -5.0; // temperature of incoming stock
  private double nonCoolingUsage = 15.0; // kW nominal
  private double ncUsageVariability = 0.2; // for m-r random walk
  private double ncMeanReversion = 0.05;
  private double unitSize = 50.0; // tons

  // model state
  private RandomSeed opSeed;
  private NormalDistribution normal01;
  private RandomSeed evalSeed;
  private Double currentTemp = null;
  private double currentStock = 0.0;
  //private Double lastTemp = null;
  //private double coolingEnergyUsed = 0.0;
  private double totalEnergyUsed = 0.0;
  private double currentNcUsage;
  private double coolingLossPerK = 0.0; // kWh/K -- lazy computation

  private TariffEvaluator tariffEvaluator;
  private int profileSize = 14; // two weeks of weather data

  /**
   * Default constructor, requires manual setting of name
   */
  public ColdStorage ()
  {
    super();
  }

  /**
   * Constructor with name
   */
  public ColdStorage (String name)
  {
    super(name);
  }

  @Override
  public void initialize ()
  {
    log.info("Initialize " + name);
    // fill out CustomerInfo
    getCustomerInfo().withPowerType(PowerType.THERMAL_STORAGE_CONSUMPTION)
        .withControllableKW(unitSize / cop)
        .withStorageCapacity(stockCapacity * CP_ICE * (maxTemp - minTemp))
        .withUpRegulationKW(unitSize / cop)
        .withDownRegulationKW(unitSize / cop); // optimistic, perhaps
    ensureSeeds();
    // randomize current temp
    setCurrentTemp(minTemp + (maxTemp - minTemp) * opSeed.nextDouble());
    currentNcUsage = nonCoolingUsage;
    currentStock = stockCapacity;
    // set up the tariff evaluator. We are wide-open to variable pricing.
    tariffEvaluator = new TariffEvaluator(this);
    tariffEvaluator.withInertia(0.7).withPreferredContractDuration(14);
    tariffEvaluator.initializeInconvenienceFactors(0.0, 0.01, 0.0, 0.0);
    tariffEvaluator.initializeRegulationFactors(unitSize * TON_CONVERSION * 0.1,
                                                0.0,
                                                unitSize * TON_CONVERSION * 0.1);
  }

  // Gets a new random-number opSeed just in case we don't already have one.
  // Useful for mock-based testing.
  private void ensureSeeds ()
  {
    if (null == opSeed) {
      opSeed =
        randomSeedRepo.getRandomSeed(ColdStorage.class.getName() + "-" + name,
                                     0, "model");
      evalSeed =
        randomSeedRepo.getRandomSeed(ColdStorage.class.getName() + "-" + name,
                                     0, "eval");
      normal01 = new NormalDistribution(0.0, 1.0);
      normal01.reseedRandomGenerator(opSeed.nextLong());
    }
  }

  // ----------------------- Run the model ------------------------
  @Override
  public void step ()
  {
    totalEnergyUsed = 0.0;

    // First, we have to account for controls exercised in the last timeslot.
    // If there was non-zero regulation, we have to adjust the temperature.
    double regulation = getSubscription().getRegulation();
    if (regulation != 0.0) {
      // positive value is up-regulation, which means we lost that much
      double tempChange = regulation * cop / currentStock / CP_ICE;
      log.info(getName() + ": regulation = " + regulation
               + ", tempChange = " + tempChange);
      currentTemp += tempChange;
    }

    // add in temp change due to stock turnover
    currentTemp += turnoverRise();

    // start with the non-cooling load - this part is not subject to regulation
    updateNcUsage();
    useEnergy(currentNcUsage);

    // use cooling energy to maintain and adjust current temp
    WeatherReport weather = weatherReportRepo.currentWeatherReport();
    double outsideTemp = weather.getTemperature();
    double energyNeeded =
        computeCoolingEnergy(outsideTemp, unitSize * TON_CONVERSION);

    // Now we need to record available regulation capacity. Note that only
    // the cooling portion is available for regulation.
    RegulationCapacity capacity =
      new RegulationCapacity(energyNeeded / cop,
                             -(unitSize * TON_CONVERSION - energyNeeded) / cop);
    getSubscription().setRegulationCapacity(capacity);
    log.info(getName()
             + ": regulation capacity (" + capacity.getUpRegulationCapacity()
             + ", " + capacity.getDownRegulationCapacity() + ")");

    useEnergy(energyNeeded / cop);

    log.debug("total energy = " + totalEnergyUsed);
    getSubscription().usePower(totalEnergyUsed);
  }

  // digs out the current subscription for this thing. Since the population is
  // always one, there should only every be one of them
  private TariffSubscription getSubscription ()
  {
    return getCurrentSubscriptions().get(0);
  }

  // separated out to help create profiles
  double computeCoolingEnergy (double outsideTemp, double maxAvail)
  {
    double coolingLoss = computeCoolingLoss(outsideTemp);
    // at this point, coolingLoss is the energy needed to maintain current temp
    double adjustmentCooling = 0.0;
    if (getCurrentTemp() < (getNominalTemp() - 0.2)) {
      // go to nominal as quickly as possible
      double maxWarming = coolingLoss;
      double neededWarming =
          currentStock * CP_ICE * (getNominalTemp() - getCurrentTemp());
      adjustmentCooling = -Math.min(maxWarming, neededWarming);
    }
    else if (getCurrentTemp() > (getNominalTemp() + 0.2)) {
      double maxCooling = maxAvail - coolingLoss;
      double neededCooling =
          currentStock * CP_ICE * (getCurrentTemp() - getNominalTemp());
      adjustmentCooling = Math.min(neededCooling, maxCooling);
    }
    currentTemp -= adjustmentCooling / (currentStock * CP_ICE);
    double energyNeeded = coolingLoss + adjustmentCooling;
    log.info(getName() + ": temp = " + currentTemp + ", adjustmentCooling = "
             + adjustmentCooling + ", total cooling energy = " + energyNeeded
             + ", temp change = "
             + (-adjustmentCooling / (currentStock * CP_ICE)));
    return energyNeeded;
  }

  // computes rise in temperature due to stock turnover
  double turnoverRise ()
  {
    double turnoverMean = turnoverRatio * stockCapacity / 24.0;
    double sd = turnoverSd * stockCapacity / 24.0;
    // draw turnover quantity this hour from normal distribution
    double outgoing =
        Math.max(0.0, (normal01.sample() * sd + turnoverMean));
    double incoming =
        Math.max(0.0, (normal01.sample() * sd + turnoverMean));
    currentStock -=  outgoing;
    double newStock = incoming; // daily-hourly
    double newTemp =
      ((currentStock * currentTemp + newStock * newStockTemp)
          / (currentStock + newStock));
    log.info(getName() + ": remove " + outgoing + "T, add " + incoming
             + "T raises temp " + (newTemp - currentTemp) + "K");
    currentStock += incoming;
    return (newTemp - currentTemp);
  }

  void updateNcUsage() // pkg visibility for testing
  {
    if (ncUsageVariability == 0)
      return;
    currentNcUsage = currentNcUsage
        + (nonCoolingUsage
            * (ncUsageVariability * (opSeed.nextDouble() * 2.0 - 1.0)))
            + ncMeanReversion * (nonCoolingUsage - currentNcUsage);
    log.info(getName() + ": Non-cooling usage = " + currentNcUsage);
  }

  // computes kWh cooling energy to maintain current inside temp
  double computeCoolingLoss (double outsideTemp)
  {
    double upperLoss = getCoolingLossPerK() * (outsideTemp - currentTemp);
    double floorLoss =
      (R_CONVERSION / getFloorRValue() * getRoofArea())
          * (GROUND_TEMP - currentTemp);
    log.info(getName() + ": heat loss walls & roof: " + upperLoss
             + ", floor: " + floorLoss
             + ", heat load: " + currentNcUsage);
    return upperLoss + floorLoss + currentNcUsage;
  }

  // Lazy evaluation for walls + roof + infiltration loss rate kW per K
  double getCoolingLossPerK ()
  {
    if (0.0 == coolingLossPerK) {
      double roofLoss = R_CONVERSION / getRoofRValue() * getRoofArea();
      double wallLoss = R_CONVERSION / getWallRValue() * getWallArea();
      double infiltrationLoss = getInfiltrationRatio() * (roofLoss + wallLoss);
      log.debug(": Heat loss per K -- roof: " + roofLoss
                + ", walls: " + wallLoss
                + ", infiltration: " + infiltrationLoss);
      coolingLossPerK = roofLoss + wallLoss + infiltrationLoss;
    }
    return coolingLossPerK;
  }

  // -------------------------- Evaluate tariffs ------------------------
  @Override
  public void evaluateTariffs (List<Tariff> tariffs)
  {
    tariffEvaluator.evaluateTariffs();
  }

  // ------------- CustomerModelAccessor methods -----------------
  @Override
  public double[] getCapacityProfile (Tariff tariff)
  {
    List<WeatherReport> weather = weatherReportRepo.allWeatherReports();
    double[] result = new double[profileSize];
    for (int i = 0; i < profileSize; i++) {
      int wi = i + weather.size() - profileSize;
      double cooling =
        computeCoolingEnergy(weather.get(wi).getTemperature(),
                             unitSize * TON_CONVERSION);
      result[i] = cooling / cop + nonCoolingUsage;
    }
    return result;
  }

  @Override
  public double getBrokerSwitchFactor (boolean isSuperseding)
  {
    if (isSuperseding)
      return 0;
    else
      return 0.02;
  }

  @Override
  public double getTariffChoiceSample ()
  {
    return evalSeed.nextDouble();
  }

  @Override
  public double getInertiaSample ()
  {
    return evalSeed.nextDouble();
  }

  // --------------- State and state change -------------
  public double getCurrentTemp ()
  {
    return currentTemp;
  }

  void setCurrentTemp (double temp)
  {
    currentTemp = temp;
  }
  
  void useEnergy (double kWh)
  {
    totalEnergyUsed += kWh;
  }

  double getCurrentNcUsage ()
  {
    return currentNcUsage;
  }

  // ----------------- Parameter access -----------------

  public double getMinTemp ()
  {
    return minTemp;
  }

  @ConfigurableValue(valueType = "Double",
      description = "minimum allowable temperature")
  @StateChange
  public ColdStorage withMinTemp (double temp)
  {
    minTemp = temp;
    return this;
  }

  public double getMaxTemp ()
  {
    return maxTemp;
  }

  @ConfigurableValue(valueType = "Double",
      description = "maximum allowable temperature")
  @StateChange
  public ColdStorage withMaxTemp (double temp)
  {
    maxTemp = temp;
    return this;
  }

  public double getNominalTemp ()
  {
    return nominalTemp;
  }

  @ConfigurableValue(valueType = "Double",
      description = "nominal internal temperature")
  @StateChange
  public ColdStorage withNominalTemp (double temp)
  {
    maxTemp = temp;
    return this;
  }

  public double getNewStockTemp ()
  {
    return newStockTemp;
  }

  @ConfigurableValue(valueType = "Double",
      description = "Temperature of incoming stock")
  @StateChange
  public ColdStorage withNewStockTemp (double temp)
  {
    newStockTemp = temp;
    return this;
  }

  public double getStockCapacity ()
  {
    return stockCapacity;
  }

  @ConfigurableValue(valueType = "Double",
      description = "Typical inventory in tonnes of H2O")
  @StateChange
  public ColdStorage withStockCapacity (double value)
  {
    if (value < 0.0)
      log.error(getName() + ": Negative stock capacity " + value
                + " not allowed");
    else
      stockCapacity = value;
    return this;
  }

  public double getTurnoverRatio ()
  {
    return turnoverRatio;
  }

  @ConfigurableValue(valueType = "Double",
      description = "Ratio of stock that gets replaced daily")
  @StateChange
  public ColdStorage withTurnoverRatio (double ratio)
  {
    if (ratio < 0.0 || ratio > 1.0)
      log.error(getName() + ": turnover ratio " + ratio + " out of range");
    else
      turnoverRatio = ratio;
    return this;
  }

  public double getRoofArea ()
  {
    return roofArea;
  }

  @ConfigurableValue(valueType = "Double",
      description = "Area of roof")
  @StateChange
  public ColdStorage withRoofArea (double area)
  {
    roofArea = area;
    return this;
  }

  public double getRoofRValue ()
  {
    return roofRValue;
  }

  @ConfigurableValue(valueType = "Double",
      description = "R-value of roof insulation")
  @StateChange
  public ColdStorage withRoofRValue (double value)
  {
    roofRValue = value;
    return this;
  }

  public double getWallArea ()
  {
    return wallArea;
  }

  @ConfigurableValue(valueType = "Double",
      description = "Total area of outside walls")
  @StateChange
  public ColdStorage withWallArea (double area)
  {
    wallArea = area;
    return this;
  }

  public double getWallRValue ()
  {
    return wallRValue;
  }

  @ConfigurableValue(valueType = "Double",
      description = "R-value of wall insulation")
  @StateChange
  public ColdStorage withWallRValue (double value)
  {
    wallRValue = value;
    return this;
  }

  public double getFloorRValue ()
  {
    return floorRValue;
  }

  @ConfigurableValue(valueType = "Double",
      description = "R-value of floor insulation")
  @StateChange
  public ColdStorage withFloorRValue (double value)
  {
    floorRValue = value;
    return this;
  }

  public double getInfiltrationRatio ()
  {
    return infiltrationRatio;
  }

  @ConfigurableValue(valueType = "Double",
      description = "Infiltration loss as proportion of wall + roof loss")
  @StateChange
  public ColdStorage withInfiltrationRatio (double value)
  {
    if (value < 0.0)
      log.error(getName() + ": Infiltration ratio " + value
                + " cannot be negative");
    else
      infiltrationRatio = value;
    return this;
  }

  public double getUnitSize ()
  {
    return unitSize;
  }

  @ConfigurableValue(valueType = "Double",
      description = "Thermal capacity in tons of cooling plant")
  @StateChange
  public ColdStorage withUnitSize (double cap)
  {
    if (cap < 0.0)
      log.error(getName() + ": Cooling capacity " + cap
                + " cannot be negative");
    else
      unitSize = cap;
    return this;
  }

  public double getCop ()
  {
    return cop;
  }

  @ConfigurableValue(valueType = "Double",
      description = "Coefficient of Performance of refrigeration unit")
  @StateChange
  public ColdStorage withCop (double value)
  {
    if (value < 0.0)
      log.error(getName() + ": Coefficient of performance " + value
                + " cannot be negative");
    else
      cop = value;
    return this;
  }

  public double getNonCoolingUsage ()
  {
    return nonCoolingUsage;
  }

  @ConfigurableValue(valueType = "Double",
      description = "Mean hourly energy usage for non-cooling purposes")
  @StateChange
  public ColdStorage withNonCoolingUsage (double value)
  {
    if (value < 0.0)
      log.error(getName() + ": Non-cooling usage " + value
                + " cannot be negative");
    else
      nonCoolingUsage = value;
    return this;
  }
}
