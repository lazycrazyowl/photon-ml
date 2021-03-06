/*
 * Copyright 2017 LinkedIn Corp. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linkedin.photon.ml.optimization

import java.util.Random

import scala.math.abs

import breeze.linalg.{DenseMatrix, DenseVector, Vector, diag, pinv}
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.mockito.Mockito._
import org.testng.Assert._
import org.testng.annotations.{DataProvider, Test}

import com.linkedin.photon.ml.constants.MathConst
import com.linkedin.photon.ml.data.LabeledPoint
import com.linkedin.photon.ml.function.L2RegularizationDiff
import com.linkedin.photon.ml.function.glm._
import com.linkedin.photon.ml.function.svm.DistributedSmoothedHingeLossFunction
import com.linkedin.photon.ml.model.Coefficients
import com.linkedin.photon.ml.normalization.{NoNormalization, NormalizationContext}
import com.linkedin.photon.ml.optimization.game.FixedEffectOptimizationConfiguration
import com.linkedin.photon.ml.supervised.classification.LogisticRegressionModel
import com.linkedin.photon.ml.supervised.model.GeneralizedLinearModel
import com.linkedin.photon.ml.test.{CommonTestUtils, SparkTestUtils}
import com.linkedin.photon.ml.util.BroadcastWrapper

/**
 * Integration tests for [[DistributedOptimizationProblem]].
 */
class DistributedOptimizationProblemIntegTest extends SparkTestUtils {

  import CommonTestUtils._
  import DistributedOptimizationProblemIntegTest._

  /**
   * Function to generate a mock [[GeneralizedLinearModel]].
   *
   * @param coefficients Model coefficients (unused)
   * @return A mocked [[GeneralizedLinearModel]]
   */
  def glmConstructorMock(coefficients: Coefficients): GeneralizedLinearModel = mock(classOf[GeneralizedLinearModel])

  /**
   * Generate unweighted benign data sets for binary classification.
   *
   * @return A Seq of [[LabeledPoint]]
   */
  def generateBenignDataSetBinaryClassification: Seq[LabeledPoint] =
    drawBalancedSampleFromNumericallyBenignDenseFeaturesForBinaryClassifierLocal(
      DATA_RANDOM_SEED,
      TRAINING_SAMPLES,
      DIMENSIONS)
      .map { obj =>
        assertEquals(obj._2.length, DIMENSIONS, "Samples should have expected lengths")
        new LabeledPoint(label = obj._1, features = obj._2)
      }
      .toList

  /**
   * Generate weighted benign data sets for binary classification.
   *
   * @return A Seq of [[LabeledPoint]]
   */
  def generateWeightedBenignDataSetBinaryClassification: Seq[LabeledPoint] = {
    val r = new Random(WEIGHT_RANDOM_SEED)

    drawBalancedSampleFromNumericallyBenignDenseFeaturesForBinaryClassifierLocal(
      DATA_RANDOM_SEED,
      TRAINING_SAMPLES,
      DIMENSIONS)
      .map { obj =>
        assertEquals(obj._2.length, DIMENSIONS, "Samples should have expected lengths")
        val weight: Double = r.nextDouble() * WEIGHT_RANDOM_MAX
        new LabeledPoint(label = obj._1, features = obj._2, weight = weight)
      }
      .toList
  }

  /**
   * Generate unweighted benign data sets for linear regression.
   *
   * @return A Seq of [[LabeledPoint]]
   */
  def generateBenignDataSetLinearRegression: Seq[LabeledPoint] =
    drawSampleFromNumericallyBenignDenseFeaturesForLinearRegressionLocal(
      DATA_RANDOM_SEED,
      TRAINING_SAMPLES,
      DIMENSIONS)
      .map({ obj =>
        assertEquals(obj._2.length, DIMENSIONS, "Samples should have expected lengths")
        new LabeledPoint(label = obj._1, features = obj._2)
      })
      .toList

  /**
   * Generate weighted benign data sets for linear regression.
   *
   * @return A Seq of [[LabeledPoint]]
   */
  def generateWeightedBenignDataSetLinearRegression: Seq[LabeledPoint] = {
    val r = new Random(WEIGHT_RANDOM_SEED)

    drawSampleFromNumericallyBenignDenseFeaturesForLinearRegressionLocal(
      DATA_RANDOM_SEED,
      TRAINING_SAMPLES,
      DIMENSIONS)
      .map { obj =>
        assertEquals(obj._2.length, DIMENSIONS, "Samples should have expected lengths")
        val weight: Double = r.nextDouble() * WEIGHT_RANDOM_MAX
        new LabeledPoint(label = obj._1, features = obj._2, weight = weight)
      }
      .toList
  }

  /**
   * Generate unweighted benign data sets for Poisson regression.
   *
   * @return A Seq of [[LabeledPoint]]
   */
  def generateBenignDataSetPoissonRegression: Seq[LabeledPoint] =
    drawSampleFromNumericallyBenignDenseFeaturesForPoissonRegressionLocal(
      DATA_RANDOM_SEED,
      TRAINING_SAMPLES,
      DIMENSIONS)
      .map({ obj =>
        assertEquals(obj._2.length, DIMENSIONS, "Samples should have expected lengths")
        new LabeledPoint(label = obj._1, features = obj._2)
      })
      .toList

  /**
   * Generate weighted benign data sets for Poisson regression.
   *
   * @return A Seq of [[LabeledPoint]]
   */
  def generateWeightedBenignDataSetPoissonRegression: Seq[LabeledPoint] = {
    val r = new Random(WEIGHT_RANDOM_SEED)

    drawSampleFromNumericallyBenignDenseFeaturesForPoissonRegressionLocal(
      DATA_RANDOM_SEED,
      TRAINING_SAMPLES,
      DIMENSIONS)
      .map { obj =>
        assertEquals(obj._2.length, DIMENSIONS, "Samples should have expected lengths")
        val weight: Double = r.nextDouble() * WEIGHT_RANDOM_MAX
        new LabeledPoint(label = obj._1, features = obj._2, weight = weight)
      }
      .toList
  }

  @DataProvider(parallel = true)
  def variancesSimpleInput(): Array[Array[Object]] =
    // Input data generation function, objective function, manual Hessian calculation function
    Array(
      Array(generateBenignDataSetBinaryClassification _, LogisticLossFunction, logisticHessianSum _),
      Array(generateBenignDataSetLinearRegression _, SquaredLossFunction, linearHessianSum _),
      Array(generateBenignDataSetPoissonRegression _, PoissonLossFunction, poissonHessianSum _))

  @DataProvider(parallel = true)
  def variancesComplexInput(): Array[Array[Object]] = {
    val regularizationWeights = Array[java.lang.Double](0.1, 1.0, 10.0, 100.0)

    // Regularization weight, input data generation function, objective function, manual Hessian calculation function
    regularizationWeights.flatMap { weight =>
      Array(
        Array[Object](
          weight,
          generateWeightedBenignDataSetBinaryClassification _,
          LogisticLossFunction,
          logisticHessianSum _),
        Array[Object](
          weight,
          generateWeightedBenignDataSetLinearRegression _,
          SquaredLossFunction,
          linearHessianSum _),
        Array[Object](
          weight,
          generateWeightedBenignDataSetPoissonRegression _,
          PoissonLossFunction,
          poissonHessianSum _))
    }
  }

  /**
   * Test that regularization weights can be updated.
   */
  @Test
  def testUpdateRegularizationWeight(): Unit = sparkTest("checkEasyTestFunctionSparkNoInitialValue") {
    val initL1Weight = 1D
    val initL2Weight = 2D
    val finalL1Weight = 3D
    val finalL2Weight = 4D
    val finalElasticWeight = 5D
    val alpha = 0.75
    val elasticFinalL1Weight = finalElasticWeight * alpha
    val elasticFinalL2Weight = finalElasticWeight * (1 - alpha)

    val optimizerL1 = new OWLQN(initL1Weight, NORMALIZATION_MOCK)
    val optimizer = mock(classOf[Optimizer[DistributedSmoothedHingeLossFunction]])
    val statesTracker = mock(classOf[OptimizationStatesTracker])
    val objectiveFunction = mock(classOf[DistributedSmoothedHingeLossFunction])
    val objectiveFunctionL2 = new L2LossFunction(sc)
    objectiveFunctionL2.l2RegularizationWeight = initL2Weight

    doReturn(Some(statesTracker)).when(optimizer).getStateTracker

    val l1Problem = new DistributedOptimizationProblem(
      optimizerL1,
      objectiveFunction,
      samplerOption = None,
      LogisticRegressionModel.apply,
      L1RegularizationContext,
      isComputingVariances = false)
    val l2Problem = new DistributedOptimizationProblem(
      optimizer,
      objectiveFunctionL2,
      samplerOption = None,
      LogisticRegressionModel.apply,
      L2RegularizationContext,
      isComputingVariances = false)
    val elasticProblem = new DistributedOptimizationProblem(
      optimizerL1,
      objectiveFunctionL2,
      samplerOption = None,
      LogisticRegressionModel.apply,
      ElasticNetRegularizationContext(alpha),
      isComputingVariances = false)

    // Check update to L1/L2 weights individually
    assertNotEquals(optimizerL1.l1RegularizationWeight, finalL1Weight, CommonTestUtils.HIGH_PRECISION_TOLERANCE)
    assertNotEquals(objectiveFunctionL2.l2RegularizationWeight, finalL2Weight, CommonTestUtils.HIGH_PRECISION_TOLERANCE)
    assertEquals(optimizerL1.l1RegularizationWeight, initL1Weight, CommonTestUtils.HIGH_PRECISION_TOLERANCE)
    assertEquals(objectiveFunctionL2.l2RegularizationWeight, initL2Weight, CommonTestUtils.HIGH_PRECISION_TOLERANCE)

    l1Problem.updateRegularizationWeight(finalL1Weight)
    l2Problem.updateRegularizationWeight(finalL2Weight)

    assertNotEquals(optimizerL1.l1RegularizationWeight, initL1Weight, CommonTestUtils.HIGH_PRECISION_TOLERANCE)
    assertNotEquals(objectiveFunctionL2.l2RegularizationWeight, initL2Weight, CommonTestUtils.HIGH_PRECISION_TOLERANCE)
    assertEquals(optimizerL1.l1RegularizationWeight, finalL1Weight, CommonTestUtils.HIGH_PRECISION_TOLERANCE)
    assertEquals(objectiveFunctionL2.l2RegularizationWeight, finalL2Weight, CommonTestUtils.HIGH_PRECISION_TOLERANCE)

    // Check updates to L1/L2 weights together
    optimizerL1.l1RegularizationWeight = initL1Weight
    objectiveFunctionL2.l2RegularizationWeight = initL2Weight

    assertNotEquals(optimizerL1.l1RegularizationWeight, elasticFinalL1Weight, CommonTestUtils.HIGH_PRECISION_TOLERANCE)
    assertNotEquals(objectiveFunctionL2.l2RegularizationWeight, elasticFinalL2Weight, CommonTestUtils.HIGH_PRECISION_TOLERANCE)
    assertEquals(optimizerL1.l1RegularizationWeight, initL1Weight, CommonTestUtils.HIGH_PRECISION_TOLERANCE)
    assertEquals(objectiveFunctionL2.l2RegularizationWeight, initL2Weight, CommonTestUtils.HIGH_PRECISION_TOLERANCE)

    elasticProblem.updateRegularizationWeight(finalElasticWeight)

    assertNotEquals(optimizerL1.l1RegularizationWeight, initL1Weight, CommonTestUtils.HIGH_PRECISION_TOLERANCE)
    assertNotEquals(objectiveFunctionL2.l2RegularizationWeight, initL2Weight, CommonTestUtils.HIGH_PRECISION_TOLERANCE)
    assertEquals(optimizerL1.l1RegularizationWeight, elasticFinalL1Weight, CommonTestUtils.HIGH_PRECISION_TOLERANCE)
    assertEquals(objectiveFunctionL2.l2RegularizationWeight, elasticFinalL2Weight, CommonTestUtils.HIGH_PRECISION_TOLERANCE)
  }

  /**
   * Test coefficient variance computation for unweighted data points, without regularization.
   *
   * @param dataGenerationFunction Function to generate test data
   * @param lossFunction Loss function for optimization
   * @param resultDirectDerivationFunction Function to compute coefficient variance directly
   */
  @Test(dataProvider = "variancesSimpleInput")
  def testComputeVariancesSimple(
      dataGenerationFunction: () => Seq[LabeledPoint],
      lossFunction: PointwiseLossFunction,
      resultDirectDerivationFunction: (Vector[Double]) => (DenseMatrix[Double], LabeledPoint) => DenseMatrix[Double]): Unit =
    sparkTest("testComputeVariancesSimple") {
      val input = sc.parallelize(dataGenerationFunction())
      val coefficients = generateDenseVector(DIMENSIONS)

      val optimizer = mock(classOf[Optimizer[DistributedGLMLossFunction]])
      val statesTracker = mock(classOf[OptimizationStatesTracker])
      val regContext = mock(classOf[RegularizationContext])
      val optConfig = mock(classOf[FixedEffectOptimizationConfiguration])

      doReturn(Some(statesTracker)).when(optimizer).getStateTracker
      doReturn(regContext).when(optConfig).regularizationContext
      doReturn(RegularizationType.NONE).when(regContext).regularizationType

      val objective = DistributedGLMLossFunction(optConfig, lossFunction, treeAggregateDepth = 1)

      val optimizationProblem = new DistributedOptimizationProblem(
        optimizer,
        objective,
        samplerOption = None,
        glmConstructorMock,
        NoRegularizationContext,
        isComputingVariances = true)

      val hessianMatrix = input.treeAggregate(DenseMatrix.zeros[Double](DIMENSIONS, DIMENSIONS))(
        seqOp = resultDirectDerivationFunction(coefficients),
        combOp = (matrix1: DenseMatrix[Double], matrix2: DenseMatrix[Double]) => matrix1 + matrix2,
        depth = 1)

      val expected = diag(pinv(hessianMatrix))
      val actual: Vector[Double] = optimizationProblem.computeVariances(input, coefficients).get

      assertEquals(actual.length, DIMENSIONS)
      assertEquals(actual.length, expected.length)
      for (i <- 0 until DIMENSIONS) {
        assertEquals(actual(i), expected(i), CommonTestUtils.HIGH_PRECISION_TOLERANCE)
      }
    }

  /**
   * Test coefficient variance computation for weighted data points, with regularization.
   *
   * @param regularizationWeight The regularization weight
   * @param dataGenerationFunction Function to generate test data
   * @param lossFunction Loss function for optimization
   * @param resultDirectDerivationFunction Function to compute coefficient variance directly
   */
  @Test(dataProvider = "variancesComplexInput")
  def testComputeVariancesComplex(
      regularizationWeight: Double,
      dataGenerationFunction: () => Seq[LabeledPoint],
      lossFunction: PointwiseLossFunction,
      resultDirectDerivationFunction: (Vector[Double]) => (DenseMatrix[Double], LabeledPoint) => DenseMatrix[Double]): Unit =
    sparkTest("testComputeVariancesComplex") {
      val input = sc.parallelize(dataGenerationFunction())
      val coefficients = generateDenseVector(DIMENSIONS)

      val optimizer = mock(classOf[Optimizer[DistributedGLMLossFunction]])
      val statesTracker = mock(classOf[OptimizationStatesTracker])
      val regContext = mock(classOf[RegularizationContext])
      val optConfig = mock(classOf[FixedEffectOptimizationConfiguration])

      doReturn(Some(statesTracker)).when(optimizer).getStateTracker
      doReturn(regContext).when(optConfig).regularizationContext
      doReturn(regularizationWeight).when(optConfig).regularizationWeight
      doReturn(RegularizationType.L2).when(regContext).regularizationType
      doReturn(regularizationWeight).when(regContext).getL2RegularizationWeight(regularizationWeight)

      val objective = DistributedGLMLossFunction(optConfig, lossFunction, treeAggregateDepth = 1)

      val optimizationProblem = new DistributedOptimizationProblem(
        optimizer,
        objective,
        samplerOption = None,
        glmConstructorMock,
        L2RegularizationContext,
        isComputingVariances = true)

      val hessianMatrix = input.treeAggregate(DenseMatrix.zeros[Double](DIMENSIONS, DIMENSIONS))(
        seqOp = resultDirectDerivationFunction(coefficients),
        combOp = (matrix1: DenseMatrix[Double], matrix2: DenseMatrix[Double]) => matrix1 + matrix2,
        depth = 1)
      val hessianMatrixWithL2 = hessianMatrix + regularizationWeight * DenseMatrix.eye[Double](DIMENSIONS)

      val expected = diag(pinv(hessianMatrixWithL2))
      val actual: Vector[Double] = optimizationProblem.computeVariances(input, coefficients).get

      assertEquals(actual.length, DIMENSIONS)
      assertEquals(actual.length, expected.length)
      for (i <- 0 until DIMENSIONS) {
        assertEquals(actual(i), expected(i), CommonTestUtils.HIGH_PRECISION_TOLERANCE)
      }
    }

  /**
   * Test the variance computation against a reference implementation in R glm
   */
  @Test
  def testComputeVariancesAgainstReference(): Unit = sparkTest("testComputeVariancesAgainstReference") {
    // Read the "heart disease" dataset from libSVM format
    val input: RDD[LabeledPoint] = {
      val tt = getClass.getClassLoader.getResource("DriverIntegTest/input/heart.txt")
      val inputFile = tt.toString
      val rawInput = sc.textFile(inputFile, 1)
      rawInput.map(x => {
        val y = x.split(" ")
        val label = y(0).toDouble / 2 + 0.5
        val features = y.drop(1).map(z => z.split(":")(1).toDouble) :+ 1.0
        new LabeledPoint(label, DenseVector(features))
      }).persist()
    }

    val optimizer = mock(classOf[Optimizer[DistributedGLMLossFunction]])
    val statesTracker = mock(classOf[OptimizationStatesTracker])
    val regContext = mock(classOf[RegularizationContext])
    val optConfig = mock(classOf[FixedEffectOptimizationConfiguration])

    doReturn(Some(statesTracker)).when(optimizer).getStateTracker
    doReturn(regContext).when(optConfig).regularizationContext
    doReturn(RegularizationType.NONE).when(regContext).regularizationType

    val objective = DistributedGLMLossFunction(optConfig, LogisticLossFunction, treeAggregateDepth = 1)

    val optimizationProblem = new DistributedOptimizationProblem(
      optimizer,
      objective,
      samplerOption = None,
      glmConstructorMock,
      NoRegularizationContext,
      isComputingVariances = true)

    // From a prior optimization run
    val coefficients = DenseVector(
      -0.022306127,
      1.299914831,
      0.792316427,
      0.033470557,
      0.004679123,
      -0.459432925,
      0.294831754,
      -0.023566341,
      0.890054910,
      0.410533616,
      0.216417307,
      1.167698255,
      0.367261286,
      -8.303806435)

    // Produced by the reference implementation in R glm
    val expected = DenseVector(
      0.0007320271,
      0.3204454,
      0.05394657,
      0.0001520536,
      1.787598e-05,
      0.3898167,
      0.04483891,
      0.0001226556,
      0.2006968,
      0.05705076,
      0.1752335,
      0.08054471,
      0.01292064,
      10.37188)

    val actual: Vector[Double] = optimizationProblem.computeVariances(input, coefficients).get

    assertEquals(actual.length, expected.length)
    for (i <- 0 until expected.length) {
      val relDiff = abs(actual(i) / expected(i) - 1)
      assertTrue(relDiff < 0.001)
    }
  }

  /**
   * Test a mock run-through of the optimization problem.
   */
  @Test
  def testRun(): Unit = {
    val coefficients = new Coefficients(generateDenseVector(DIMENSIONS))

    val trainingData = mock(classOf[RDD[LabeledPoint]])
    val optimizer = mock(classOf[Optimizer[DistributedGLMLossFunction]])
    val statesTracker = mock(classOf[OptimizationStatesTracker])
    val objectiveFunction = mock(classOf[DistributedGLMLossFunction])
    val initialModel = mock(classOf[GeneralizedLinearModel])

    doReturn(true).when(optimizer).isTrackingState
    doReturn(Some(statesTracker)).when(optimizer).getStateTracker

    val problem = new DistributedOptimizationProblem(
      optimizer,
      objectiveFunction,
      samplerOption = None,
      LogisticRegressionModel.apply,
      NoRegularizationContext,
      isComputingVariances = false)

    doReturn(NORMALIZATION_MOCK).when(optimizer).getNormalizationContext
    doReturn(coefficients).when(initialModel).coefficients
    doReturn((coefficients.means, None))
      .when(optimizer)
      .optimize(objectiveFunction, coefficients.means)(trainingData)
    val state = OptimizerState(coefficients.means, 0, generateDenseVector(DIMENSIONS), 0)
    doReturn(Array(state)).when(statesTracker).getTrackedStates

    val model = problem.run(trainingData, initialModel)

    assertEquals(coefficients, model.coefficients)
    assertEquals(problem.getModelTracker.get.length, 1)
  }
}

object DistributedOptimizationProblemIntegTest {
  private val DATA_RANDOM_SEED: Int = 7
  private val WEIGHT_RANDOM_SEED = 100
  private val WEIGHT_RANDOM_MAX = 10
  private val DIMENSIONS: Int = 25
  private val TRAINING_SAMPLES: Int = DIMENSIONS * DIMENSIONS
  private val NORMALIZATION = NoNormalization()
  private val NORMALIZATION_MOCK: BroadcastWrapper[NormalizationContext] = mock(classOf[BroadcastWrapper[NormalizationContext]])

  doReturn(NORMALIZATION).when(NORMALIZATION_MOCK).value

  /**
   * Point-wise Hessian diagonal computation function for linear regression.
   *
   * @param coefficients Coefficient means vector
   * @param matrix Current Hessian matrix (prior to processing the next data point)
   * @param datum The next data point to process
   * @return The updated Hessian diagonal
   */
  def linearHessianSum
    (coefficients: Vector[Double])
    (matrix: DenseMatrix[Double], datum: LabeledPoint): DenseMatrix[Double] = {

    // For linear regression, the second derivative of the loss function (with regard to z = X_i * B) is 1.
    val features: Vector[Double] = datum.features
    val weight: Double = datum.weight
    val x = features.toDenseVector.toDenseMatrix

    matrix + (weight * (x.t * x))
  }

  /**
   * Point-wise Hessian diagonal computation function for logistic regression.
   *
   * @param coefficients Coefficient means vector
   * @param matrix Current Hessian matrix (prior to processing the next data point)
   * @param datum The next data point to process
   * @return The updated Hessian diagonal
   */
  def logisticHessianSum
    (coefficients: Vector[Double])
    (matrix: DenseMatrix[Double], datum: LabeledPoint): DenseMatrix[Double] = {

    // For logistic regression, the second derivative of the loss function (with regard to z = X_i * B) is:
    //    sigmoid(z) * (1 - sigmoid(z))
    def sigmoid(z: Double): Double = 1.0 / (1.0 + math.exp(-z))

    val features: Vector[Double] = datum.features
    val weight: Double = datum.weight
    val z: Double = datum.computeMargin(coefficients)
    val sigm: Double = sigmoid(z)
    val d2lossdz2: Double = sigm * (1.0 - sigm)
    val x = features.toDenseVector.toDenseMatrix

    matrix + (weight * d2lossdz2 * (x.t * x))
  }

  /**
   * Point-wise Hessian diagonal computation function for Poisson regression.
   *
   * @param coefficients Coefficient means vector
   * @param matrix Current Hessian matrix (prior to processing the next data point)
   * @param datum The next data point to process
   * @return The updated Hessian diagonal
   */
  def poissonHessianSum
    (coefficients: Vector[Double])
    (matrix: DenseMatrix[Double], datum: LabeledPoint): DenseMatrix[Double] = {

    // For Poisson regression, the second derivative of the loss function (with regard to z = X_i * B) is e^z.
    val features: Vector[Double] = datum.features
    val weight: Double = datum.weight
    val z: Double = datum.computeMargin(coefficients)
    val d2lossdz2 = math.exp(z)
    val x = features.toDenseVector.toDenseMatrix

    matrix + (weight * d2lossdz2 * (x.t * x))
  }

  // No way to pass Mixin class type to Mockito, need to define a concrete class
  private class L2LossFunction(sc: SparkContext)
    extends DistributedSmoothedHingeLossFunction(treeAggregateDepth = 1)
    with L2RegularizationDiff
}
