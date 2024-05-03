// Spark: 3.5.1
// Local: --master 'local[2]' --driver-memory 1G
// Databricks: ...

// COMMAND ----------

/*
This example shows how to identify spill in a stage, and measure it.
It also shows just one way to work it around by increasing the number of shuffle partitions.

References: 
- https://medium.com/road-to-data-engineering/spark-performance-optimization-series-2-spill-685126e9d21f

# Symptom
A stage is particularly slow, and IO is high.

*/

// COMMAND ----------

import java.util.UUID
import org.apache.spark.sql.SparkSession

val spark: SparkSession = SparkSession.active

// See https://github.com/apache/spark/blob/master/core/src/main/scala/org/apache/spark/TestUtils.scala
class SpillListener extends org.apache.spark.scheduler.SparkListener {
  import org.apache.spark.scheduler.{SparkListenerTaskEnd,SparkListenerStageCompleted}
  import org.apache.spark.executor.TaskMetrics
  import scala.collection.mutable

  private val stageIdToTaskMetrics = new mutable.HashMap[Int, mutable.ArrayBuffer[TaskMetrics]]
  private val spilledStageIds = new mutable.HashSet[Int]

  def numSpilledStages: Int = synchronized { spilledStageIds.size }
  def report(): Unit = synchronized { println(f"Spilled Stages: ${numSpilledStages}%,d") }
  override def onTaskEnd(taskEnd: SparkListenerTaskEnd): Unit = synchronized {
    stageIdToTaskMetrics.getOrElseUpdate(taskEnd.stageId, new mutable.ArrayBuffer[TaskMetrics]) += taskEnd.taskMetrics
    spilledStageIds.clear
  }

  override def onStageCompleted(stageComplete: SparkListenerStageCompleted): Unit = synchronized {
    val stageId = stageComplete.stageInfo.stageId
    val metrics = stageIdToTaskMetrics.remove(stageId).toSeq.flatten
    val spilled = metrics.map(_.memoryBytesSpilled).sum > 0
    if (spilled) spilledStageIds += stageId
  }
}
val spillListener = new SpillListener()
spark.sparkContext.addSparkListener(spillListener)

spark.conf.set("spark.sql.adaptive.enabled", false)

spark.sparkContext.setJobDescription("Prepare input data")
val inputPath = "/tmp/perf-hikes/datasets/optd_por_public_filtered.csv"
val outputPath = "/tmp/perf-hikes/sandbox/" + UUID.randomUUID()
val df = spark.read.option("delimiter", "^").option("header", "true").csv(inputPath)
(1 to 300).map(i => df.withColumn("extra", lit(i))).reduce(_ union _).write.format("parquet").save(outputPath)
val dfs = spark.read.format("parquet").load(outputPath)

spark.sparkContext.setJobDescription("Shuffle with shuffle.partitions = 1 (spill)")
spark.conf.set("spark.sql.shuffle.partitions", 1)
dfs.orderBy("name").write.format("noop").mode("overwrite").save()
spillListener.report()

spark.sparkContext.setJobDescription("Shuffle with shuffle.partitions = 100 (no spill)")
spark.conf.set("spark.sql.shuffle.partitions", 100)
dfs.orderBy("name").write.format("noop").mode("overwrite").save()
spillListener.report()
// Explore the Spark UI and look for spills on the Stages or SQL sections.

// The spill job is made of 2 stages. The second stage, that reads large shuffled data, cannot cope with all of it in a reduced amount 
// of partitions (1 in the spilling example), and has to spill. 

// Identify it in Spark UI: find the job, open its second stage, go to Tasks and see "Spill (Memory)" and "Spill (Disk)".
// Mind that Spill fields not shown in case of no spill.
// Also you can go to the SQL / DataFrame section, select the spill query, and see metrics for Sort: spill size.

// In logs: look for something like: "INFO ExternalSorter: Task 1 force spilling in-memory map to disk it will release 232.1 MB memory"
