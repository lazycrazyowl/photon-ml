/*
 * Copyright 2016 LinkedIn Corp. All rights reserved.
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
package com.linkedin.photon.ml.evaluation

import java.util.{Comparator => JComparator, Arrays => JArrays}
import java.lang.{Double => JDouble}

import scala.collection.Map

import com.linkedin.photon.ml.constants.MathConst._


/**
 * Local evaluator for binary classification problems
 *
 * @param labelAndOffsetAndWeights a (id -> (label, offset, weight)) map
 * @param defaultScore the default score used to compute the metric
 */
protected[ml] class AreaUnderROCCurveLocalEvaluator(
    labelAndOffsetAndWeights: Map[Long, (Double, Double, Double)],
    defaultScore: Double = 0.0) extends LocalEvaluator {

  def this(labels: Map[Long, Double]) = this(labels.mapValues(label => (label, DEFAULT_OFFSET, DEFAULT_WEIGHT)))

  val numLabels = labelAndOffsetAndWeights.size

  /**
   * Evaluate the scores of the model
   *
   * @param scores the scores to evaluate
   * @return score metric value
   */
  override def evaluate(scores: Map[Long, Double]): Double = {
    val scoresAndLabelAndWeight = new Array[(Double, Double, Double)](numLabels)
    var pos = 0
    labelAndOffsetAndWeights.foreach { case (id, (label, offset, weight)) =>
      val score = scores.getOrElse(id, defaultScore) + offset
      scoresAndLabelAndWeight(pos) = (score, label, weight)
      pos += 1
    }

    // directly calling scoresAndLabelAndWeight.sort would cause some performance issue with large arrays
    val comparator = new JComparator[(Double, Double, Double)]() {
      override def compare(tuple1: (Double, Double, Double), tuple2: (Double, Double, Double)): Int = {
        JDouble.compare(tuple1._1, tuple2._1)
      }
    }
    JArrays.sort(scoresAndLabelAndWeight, comparator.reversed())

    var aucAccum = 0.0
    var positiveCount = 0.0
    var negativeCount = 0.0

    scoresAndLabelAndWeight.foreach { case (score, label, weight) =>
      if (label > POSITIVE_RESPONSE_THRESHOLD) {
        positiveCount += weight
      } else {
        aucAccum += positiveCount
        negativeCount += weight
      }
    }

    //normalize AUC over the total area
    aucAccum / (positiveCount * negativeCount)
  }
}
