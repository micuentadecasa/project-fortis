import java.util.concurrent.TimeUnit

import com.microsoft.partnercatalyst.fortis.spark.Schedule
import com.microsoft.partnercatalyst.fortis.spark.instagram.{InstagramContext, InstagramLocationReceiver, Location}
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.streaming.{Seconds, StreamingContext}

object Main {
  def main(args: Array[String]) {
    val conf = new SparkConf().setAppName("Simple Application")
    val sc = new SparkContext(conf)
    val ssc = new StreamingContext(sc, Seconds(1))

    val instagramStream = ssc.receiverStream(new InstagramLocationReceiver(
      location = Location(lat = 123.1, lng = 21.2),
      auth = InstagramContext("INSERT_INSTAGRAM_ACCESS_CODE_HERE"),
      schedule = Schedule(10, TimeUnit.SECONDS)))

    instagramStream.map(x => x.link).print()

    ssc.start()
    ssc.awaitTermination()
  }
}
