package com.twitter.gizzard.scheduler

import com.twitter.conversions.time._
import org.specs.Specification
import org.specs.mock.{ClassMocker, JMocker}

import com.twitter.gizzard.nameserver._
import com.twitter.gizzard.shards._
import com.twitter.gizzard.ConfiguredSpecification


class FakeCopy(
  val sourceShardId: ShardId,
  val destinationShardId: ShardId,
  count: Int,
  nameServer: NameServer,
  scheduler: JobScheduler,
  nextCopy: => Option[FakeCopy])
extends CopyJob[AnyRef](sourceShardId, destinationShardId, count, nameServer, scheduler) {

  def serialize = Map("cursor" -> 1)

  @throws(classOf[Exception])
  def copyPage(sourceShard: RoutingNode[AnyRef], destinationShard: RoutingNode[AnyRef], count: Int) = nextCopy

  override def equals(that: Any) = that match {
    case that: FakeCopy =>
      this.sourceShardId == that.sourceShardId &&
        this.destinationShardId == that.destinationShardId
    case _ => false
  }
}

object CopyJobSpec extends ConfiguredSpecification with JMocker with ClassMocker {
  "CopyJob" should {
    val sourceShardId = ShardId("testhost", "1")
    val destinationShardId = ShardId("testhost", "2")
    val destinationShardInfo = ShardInfo(destinationShardId, "FakeShard", "", "", Busy.Normal)
    val count = CopyJob.MIN_COPY + 1
    val nextCopy = mock[FakeCopy]
    val nameServer = mock[NameServer]
    val shardManager = mock[ShardManager]
    val jobScheduler = mock[JobScheduler]
    def makeCopy(next: => Option[FakeCopy]) = new FakeCopy(sourceShardId, destinationShardId, count, nameServer, jobScheduler, next)
    val shard1 = mock[RoutingNode[AnyRef]]
    val shard2 = mock[RoutingNode[AnyRef]]

    expect {
      allowing(nameServer).shardManager willReturn shardManager
    }

    "toMap" in {
      val copy = makeCopy(Some(nextCopy))
      copy.toMap mustEqual Map(
        "source_shard_hostname" -> sourceShardId.hostname,
        "source_shard_table_prefix" -> sourceShardId.tablePrefix,
        "destination_shard_hostname" -> destinationShardId.hostname,
        "destination_shard_table_prefix" -> destinationShardId.tablePrefix,
        "count" -> count
      ) ++ copy.serialize
    }

    "toJson" in {
      val copy = makeCopy(Some(nextCopy))
      val json = copy.toJson
      json mustMatch "Copy"
      json mustMatch "\"source_shard_hostname\":\"%s\"".format(sourceShardId.hostname)
      json mustMatch "\"source_shard_table_prefix\":\"%s\"".format(sourceShardId.tablePrefix)
      json mustMatch "\"destination_shard_hostname\":\"%s\"".format(destinationShardId.hostname)
      json mustMatch "\"destination_shard_table_prefix\":\"%s\"".format(destinationShardId.tablePrefix)
      json mustMatch "\"count\":" + count
    }

    "apply" in {
      "normally" in {
        val copy = makeCopy(Some(nextCopy))
        expect {
          one(shardManager).getShard(destinationShardId) willReturn destinationShardInfo
          one(nameServer).findShardById(sourceShardId) willReturn shard1
          one(nameServer).findShardById(destinationShardId) willReturn shard2
          one(shardManager).markShardBusy(destinationShardId, Busy.Busy)
        }

        copy.apply()
      }

      "no source shard" in {
        val copy = makeCopy(Some(nextCopy))
        expect {
          one(shardManager).getShard(destinationShardId) willReturn destinationShardInfo
          one(nameServer).findShardById(sourceShardId) willThrow new NonExistentShard("foo")
        }

        copy.apply()
      }

      "no destination shard" in {
        val copy = makeCopy(Some(nextCopy))
        expect {
          one(shardManager).getShard(destinationShardId) willThrow new NonExistentShard("foo")
        }

        copy.apply()
      }

      "with a database connection timeout" in {
        val copy = makeCopy(throw new ShardDatabaseTimeoutException(100.milliseconds, sourceShardId))
        expect {
          one(shardManager).getShard(destinationShardId) willReturn destinationShardInfo
          one(nameServer).findShardById(sourceShardId) willReturn shard1
          one(nameServer).findShardById(destinationShardId) willReturn shard2
          one(shardManager).markShardBusy(destinationShardId, Busy.Busy)
          one(jobScheduler).put(copy)
        }

        copy.apply()
        copy.toMap("count") mustEqual (count * 0.9).toInt
      }

      "with a random exception" in {
        val copy = makeCopy(throw new Exception("boo"))
        expect {
          one(shardManager).getShard(destinationShardId) willReturn destinationShardInfo
          one(nameServer).findShardById(sourceShardId) willReturn shard1
          one(nameServer).findShardById(destinationShardId) willReturn shard2
          one(shardManager).markShardBusy(destinationShardId, Busy.Busy)
          one(shardManager).markShardBusy(destinationShardId, Busy.Error)
          never(jobScheduler).put(nextCopy)
        }

        copy.apply() must throwA[Exception]
      }

      "with a shard timeout" in {
        "early on" in {
          val copy = makeCopy(throw new ShardTimeoutException(100.milliseconds, sourceShardId))
          expect {
            one(shardManager).getShard(destinationShardId) willReturn destinationShardInfo
            one(nameServer).findShardById(sourceShardId) willReturn shard1
            one(nameServer).findShardById(destinationShardId) willReturn shard2
            one(shardManager).markShardBusy(destinationShardId, Busy.Busy)
            one(jobScheduler).put(copy)
          }

          copy.apply()
        }

        "after too many retries" in {
          val count = CopyJob.MIN_COPY - 1
          val copy = new FakeCopy(sourceShardId, destinationShardId, count, nameServer, jobScheduler, throw new ShardTimeoutException(100.milliseconds, sourceShardId))

          expect {
            one(shardManager).getShard(destinationShardId) willReturn destinationShardInfo
            one(nameServer).findShardById(sourceShardId) willReturn shard1
            one(nameServer).findShardById(destinationShardId) willReturn shard2
            one(shardManager).markShardBusy(destinationShardId, Busy.Busy)
            one(shardManager).markShardBusy(destinationShardId, Busy.Error)
            never(jobScheduler).put(nextCopy)
          }

          copy.apply() must throwA[Exception]
        }
      }

      "when cancelled" in {
        val copy = makeCopy(Some(nextCopy))
        val cancelledInfo = ShardInfo(destinationShardId, "FakeShard", "", "", Busy.Cancelled)

        expect {
          one(shardManager).getShard(destinationShardId) willReturn cancelledInfo
          never(nameServer).findShardById(sourceShardId)
          never(nameServer).findShardById(destinationShardId)
          never(shardManager).markShardBusy(destinationShardId, Busy.Busy)
          never(jobScheduler).put(nextCopy)
        }

        copy.apply()
      }

      "when finished" in {
        val copy = makeCopy(None)

        expect {
          one(shardManager).getShard(destinationShardId) willReturn destinationShardInfo
          one(nameServer).findShardById(sourceShardId) willReturn shard1
          one(nameServer).findShardById(destinationShardId) willReturn shard2
          one(shardManager).markShardBusy(destinationShardId, Busy.Busy)
          one(shardManager).markShardBusy(destinationShardId, Busy.Normal)
        }

        copy.apply()
      }
    }
  }
}
