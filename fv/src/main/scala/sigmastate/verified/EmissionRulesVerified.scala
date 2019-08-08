package sigmastate.verified

import stainless.lang._
import stainless.collection._
import stainless.annotation._
import stainless.math._
import stainless.proof._

import scala.annotation.tailrec

abstract class MonetarySettings {
  def fixedRatePeriod: Int

  def epochLength: Int

  def fixedRate: Long

  def oneEpochReduction: Long

  def minerRewardDelay: Int

  def foundersInitialReward: Long
}


case object MonetarySettingsErgoLaunch extends MonetarySettings {

  override val fixedRatePeriod: Int = 30 * 2 * 24 * 365 // 525600
  override val epochLength: Int = 90 * 24 * 30 // 64800
  override val fixedRate: Long = 75L * EmissionRulesVerified.CoinsInOneErgo // 75000000000
  override val oneEpochReduction: Long = 3L * EmissionRulesVerified.CoinsInOneErgo
  override val minerRewardDelay: Int = 720
  override val foundersInitialReward: Long = 75L * EmissionRulesVerified.CoinsInOneErgo / 10
}

object EmissionRulesVerified {

  val CoinsInOneErgo: Long = 1000000000

  // TODO: extract
  def max(i1: Long, i2: Long): Long = {
    if (i1 >= i2) i1 else i2
    }.ensuring(res => (res == i1 && res >= i2) || (res == i2 && res >= i1))

  // TODO: extract
  def sum(start: Int, end: Int, f: Int => Long): Long = {
    var s = 0L
    var i = start
    while (i <= end) {
      decreases(end - start)
      s = s + f(i)
      i = i + 1
    }
    s
  }


  val blocksTotal: Int = 2080799
  val coinsTotal: Long = 97739925L * CoinsInOneErgo

//  val coinsTotal: Long = totalCoinsAndBlocks(MonetarySettingsErgoLaunch)._1
//  val blocksTotal: Int = totalCoinsAndBlocks(MonetarySettingsErgoLaunch)._2

//  def checkTotal: Boolean = {
//    blocksTotal == 2080799 &&
//    coinsTotal == 97739925L * CoinsInOneErgo
//  }.holds

  val foundersCoinsTotal: Long = remainingFoundationRewardAtHeight(0, MonetarySettingsErgoLaunch)

  def checkFoundersCoinsTotal: Boolean = {
    foundersCoinsTotal == 4330792 * CoinsInOneErgo + (CoinsInOneErgo / 2)
    }.holds

  def minersCoinsTotal: Long = {
    coinsTotal - foundersCoinsTotal
  } ensuring (_ == 93409132 * CoinsInOneErgo + (CoinsInOneErgo / 2))


  @extern @pure @library
  def totalCoinsAndBlocks(settings: MonetarySettings): (Long, Int) = {
    @tailrec
    def loop(height: Int, acc: Long): (Long, Int) = {
      val currentRate = emissionAtHeight(height, settings)
      if (currentRate > 0) {
        loop(height + 1, acc + currentRate)
      } else {
        (acc, height - 1)
      }
    }
    loop(1, 0)
    }

  /**
    * Number of coins to be issued at height `h`
    */
  def emissionAtHeight(h: Int, settings: MonetarySettings): Long = {
    require(h > 0 && settings.epochLength > 0)
    if (h < settings.fixedRatePeriod) {
      settings.fixedRate
    } else {
      val epoch = 1 + (h - settings.fixedRatePeriod) / settings.epochLength
      max(settings.fixedRate - settings.oneEpochReduction * epoch, 0)
    }
  }

  def proveEmissionAtHeightErgoLaunch(h: Int): Long = {
    require(h > 0)
    emissionAtHeight(h, MonetarySettingsErgoLaunch)
  } ensuring(res => res >= 0)

  /**
    * Returns number of coins issued at height `h` in favour of a miner
    */
  def minersRewardAtHeight(h: Int, settings: MonetarySettings): Long = {
    require(settings.epochLength > 0 && settings.fixedRate > 0)
    if (h < settings.fixedRatePeriod + 2 * settings.epochLength) {
      settings.fixedRate - settings.foundersInitialReward
    } else {
      val epoch = 1 + (h - settings.fixedRatePeriod) / settings.epochLength
      max(settings.fixedRate - settings.oneEpochReduction * epoch, 0)
    }
  }

  def proveMinersRewardAtHeightErgoLaunch(h: Int): Long = {
    require(h > 0)
    minersRewardAtHeight(h, MonetarySettingsErgoLaunch)
  } ensuring (res => res >= 0)

  /**
    * Returns number of coins issued at height `h` in favour of the foundation
    */
  def foundationRewardAtHeight(h: Int, settings: MonetarySettings): Long = {
    if (h < settings.fixedRatePeriod) {
      settings.foundersInitialReward
    } else if (h < settings.fixedRatePeriod + settings.epochLength) {
      settings.foundersInitialReward - settings.oneEpochReduction
    } else if (h < settings.fixedRatePeriod + (2 * settings.epochLength)) {
      settings.foundersInitialReward - 2 * settings.oneEpochReduction
    } else {
      0
    }
  }

  def proveFoundationRewardAtHeightErgoLaunch(h: Int): Long = {
    require(h > 0)
    foundationRewardAtHeight(h, MonetarySettingsErgoLaunch)
  } ensuring (res => res >= 0)

  /**
    * Returns number of coins which should be kept in the foundation box at height `h`
    */
  def remainingFoundationRewardAtHeight(h: Int, settings: MonetarySettings): Long = {
    val foundersInitialReward = settings.foundersInitialReward
    val oneEpochReduction = settings.oneEpochReduction
    val epochLength = settings.epochLength
    val fixedRatePeriod = settings.fixedRatePeriod
    val full15reward = (foundersInitialReward - 2 * oneEpochReduction) * epochLength
    val full45reward = (foundersInitialReward - oneEpochReduction) * epochLength

    if (h < fixedRatePeriod) {
      full15reward + full45reward + (fixedRatePeriod - h - 1) * foundersInitialReward
    } else if (h < fixedRatePeriod + epochLength) {
      full15reward + (foundersInitialReward - oneEpochReduction) * (fixedRatePeriod + epochLength - h - 1)
    } else if (h < fixedRatePeriod + (2 * epochLength)) {
      (foundersInitialReward - 2 * oneEpochReduction) * (fixedRatePeriod + (2 * epochLength) - h - 1)
    } else {
      0
    }
  }

  def epochsIssued(epoch: Int, settings: MonetarySettings): Long = {
    if (epoch <= 0)
      0L
    else {
      sum(1, epoch, { i => max(settings.fixedRate - settings.oneEpochReduction * i, 0) * settings.epochLength})
    }
  }

  /**
    * Returns number of coins issued at height `h` and before that
    */
  def issuedCoinsAfterHeight(h: Int, settings: MonetarySettings): Long = {
    require(h > 0 && settings.epochLength > 0)
    if (h < settings.fixedRatePeriod) {
      settings.fixedRate * h
    } else {
      val fixedRateIssue: Long = settings.fixedRate * (settings.fixedRatePeriod - 1)
      val epoch = (h - settings.fixedRatePeriod) / settings.epochLength
      val fullEpochsIssued: Long = epochsIssued(epoch, settings)
      val heightInThisEpoch = (h - settings.fixedRatePeriod) % settings.epochLength + 1
      val rateThisEpoch = max(settings.fixedRate - settings.oneEpochReduction * (epoch + 1), 0)
      val thisEpochIssued = heightInThisEpoch * rateThisEpoch

      fullEpochsIssued + fixedRateIssue + thisEpochIssued
    }
  }

  def proveIssuedCoinsAfterHeight1ErgoLaunch: Boolean = {
    issuedCoinsAfterHeight(1, MonetarySettingsErgoLaunch) == MonetarySettingsErgoLaunch.fixedRate
  }.holds

  def proveIssuedCoinsAfterHeightBlocksTotalErgoLaunch: Boolean = {
    issuedCoinsAfterHeight(blocksTotal, MonetarySettingsErgoLaunch) == coinsTotal
  }.holds

  def proveCorrectSumFromMinerAndFoundationParts(height: Int): Boolean  = {
    require(height > 0)

    val currentRate = emissionAtHeight(height, MonetarySettingsErgoLaunch)
    val minerPart = minersRewardAtHeight(height, MonetarySettingsErgoLaunch)
    val foundationPart = foundationRewardAtHeight(height, MonetarySettingsErgoLaunch)

    foundationPart + minerPart == currentRate

  }.holds

  def collectedFoundationReward(height: Int): Long = {
    require(height > 0)
    sum(0, height, { i => foundationRewardAtHeight(i, MonetarySettingsErgoLaunch)})
  }

  // TODO fix frozen prover
//  def proveCollectedFoundationReward(height: Int): Boolean = {
//    require(height <= blocksTotal && height > 0)
//    collectedFoundationReward(height) >= 0
//  } holds because {
//    foundationRewardAtHeight(height, MonetarySettingsErgoLaunch) > 0
//  }

  val totalFoundersReward: Long = collectedFoundationReward(blocksTotal)

  // TODO fix frozen prover on post condition
  def proveCorrectRemainingFoundationRewardAtHeight(height: Int): (Long, Long) = {
    require(height <= blocksTotal && height > 0)
    val collectedFoundersPart = collectedFoundationReward(height)
    val remainingFoundersPart = remainingFoundationRewardAtHeight(height, MonetarySettingsErgoLaunch)
    (remainingFoundersPart ,collectedFoundersPart)
  } //ensuring(res => res._1 + res._2 == totalFoundersReward)

}