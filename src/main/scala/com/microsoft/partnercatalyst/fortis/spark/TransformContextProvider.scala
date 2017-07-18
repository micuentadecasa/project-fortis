package com.microsoft.partnercatalyst.fortis.spark

import java.time.Duration
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.{CompletableFuture, SynchronousQueue, TimeUnit}

import com.microsoft.azure.servicebus.primitives.ConnectionStringBuilder
import com.microsoft.azure.servicebus._
import com.microsoft.partnercatalyst.fortis.spark.TransformContextProvider._
import com.microsoft.partnercatalyst.fortis.spark.dba.ConfigurationManager
import com.microsoft.partnercatalyst.fortis.spark.dto.{BlacklistedTerm, SiteSettings}
import com.microsoft.partnercatalyst.fortis.spark.logging.Loggable
import com.microsoft.partnercatalyst.fortis.spark.transforms.image.{ImageAnalysisAuth, ImageAnalyzer}
import com.microsoft.partnercatalyst.fortis.spark.transforms.language.{LanguageDetector, LanguageDetectorAuth}
import com.microsoft.partnercatalyst.fortis.spark.transforms.locations.LocationsExtractorFactory
import com.microsoft.partnercatalyst.fortis.spark.transforms.locations.client.FeatureServiceClient
import com.microsoft.partnercatalyst.fortis.spark.transforms.sentiment.SentimentDetectorAuth
import org.apache.spark.SparkContext

import scala.util.Properties

class TransformContextProvider(configManager: ConfigurationManager, featureServiceClient: FeatureServiceClient) extends Serializable with Loggable {
  private val deltaChannel: SynchronousQueue[Delta] = new SynchronousQueue[Delta]()
  private val writeLock: ReentrantLock = new ReentrantLock(true)

  // Do not serialize these values. The transformContext would otherwise contain stale Broadcast
  // instances, so we rebuild it on recovery from checkpoint.
  @volatile @transient private var transformContext: TransformContext = null
  @volatile @transient private var queueClient: QueueClient = null

  def getOrUpdateContext(sparkContext: SparkContext): TransformContext = {
    ensureInitialized(sparkContext)

    writeLock.lock()
    try {
      // Grab the next delta from the service bus client thread if one is ready.
      // If not, either no update requests have arrived on the service bus queue, or the service bus client thread is
      // still fulfilling a request (and hence the Delta is not yet available). Return the current context.
      val delta = Option(deltaChannel.poll(0, TimeUnit.SECONDS))

      if (delta.isDefined) {
        updateTransformContextAndBroadcast(delta.get, sparkContext)
      }
    } finally {
      writeLock.unlock()
    }

    transformContext
  }

  private def ensureInitialized(sparkContext: SparkContext): Unit = {
    if (transformContext == null) {
      // Blocking init of transform context and queue client used for non-blocking updates.
      // Initialization has not yet occurred since construction/deserialization.
      writeLock.lock()
      try {
        if (transformContext == null) {

          // Fetch data synchronously
          val siteSettings = configManager.fetchSiteSettings()
          val langToWatchlist = configManager.fetchWatchlist()
          val blacklist = configManager.fetchBlacklist()

          var delta = deltaWithSettings(siteSettings)
          delta = deltaWithWatchlist(langToWatchlist, delta)
          delta = deltaWithBlacklist(blacklist, delta)

          updateTransformContextAndBroadcast(delta, sparkContext)
          startQueueClient()
        }
      } finally {
        writeLock.unlock()
      }
    }
  }

  private def startQueueClient(): Unit = {
    queueClient = new QueueClient(
      new ConnectionStringBuilder(
        Properties.envOrNone(Constants.Env.ManagementBusNamespace).get,
        Properties.envOrNone(Constants.Env.ManagementBusConfigQueueName).get,
        Properties.envOrNone(Constants.Env.ManagementBusPolicyName).get,
        Properties.envOrNone(Constants.Env.ManagementBusPolicyKey).get
      ), ReceiveMode.PeekLock)

    queueClient.registerMessageHandler(
      new MessageHandler(),
      new MessageHandlerOptions(
        1, // Max concurrent calls
        true,
        Duration.ofMinutes(5)))
  }

  /**
    * Creates a new transform context from the current one, overwriting any corresponding fields that are
    * non-empty in the provided delta. Broadcast fields are broadcasted here as well, as needed.
    *
    * Note: this is only called by Spark threads.
    */
  private def updateTransformContextAndBroadcast(delta: Delta, sparkContext: SparkContext): Unit = {
    if (transformContext == null) {
      transformContext = TransformContext()
    }

    transformContext = transformContext.copy(
      siteSettings = delta.siteSettings.getOrElse(transformContext.siteSettings),
      langToWatchlist = delta.langToWatchlist match {
        case Some(list) => sparkContext.broadcast(list)
        case None => transformContext.langToWatchlist
      },
      blacklist = delta.blacklist match {
        case Some(list) => sparkContext.broadcast(list)
        case None => transformContext.blacklist
      },
      locationsExtractorFactory = delta.locationsExtractorFactory match {
        case Some(factory) => sparkContext.broadcast(factory)
        case None => transformContext.locationsExtractorFactory
      },
      imageAnalyzer = delta.imageAnalyzer.getOrElse(transformContext.imageAnalyzer),
      languageDetector = delta.languageDetector.getOrElse(transformContext.languageDetector),
      sentimentDetectorAuth = delta.sentimentDetectorAuth.getOrElse(transformContext.sentimentDetectorAuth)
    )
  }

  /**
    * Builds a delta against the current transform context using the provided site settings.
    * @param delta An existing delta to be used as a base.
    * @return
    */
  private def deltaWithSettings(siteSettings: SiteSettings, delta: Delta = Delta()): Delta = {
    // Note that parameters are call-by-name
    def updatedField[T](isDirty: => Boolean, newVal: => T): Option[T] = {
      // We resolve 'isDirty' if and only if the transform context is non-null. This allows the expression named by
      // 'isDirty' to assume that the transform context will not be null.
      // When the context is null, we treat the field as dirty since it requires initialization.
      if (transformContext == null || isDirty)
        Some(newVal)
      else
        None
    }

    delta.copy(
      siteSettings = Some(siteSettings),
      locationsExtractorFactory = updatedField(
        siteSettings.geofence != transformContext.siteSettings.geofence,
        new LocationsExtractorFactory(featureServiceClient, siteSettings.geofence).buildLookup()
      ),
      imageAnalyzer = updatedField(
        siteSettings.cogVisionSvcToken != transformContext.siteSettings.cogVisionSvcToken,
        new ImageAnalyzer(ImageAnalysisAuth(siteSettings.cogVisionSvcToken), featureServiceClient)
      ),
      languageDetector = updatedField(
        siteSettings.translationSvcToken != transformContext.siteSettings.translationSvcToken,
        new LanguageDetector(LanguageDetectorAuth(siteSettings.translationSvcToken))
      ),
      sentimentDetectorAuth = updatedField(
        siteSettings.translationSvcToken != transformContext.siteSettings.translationSvcToken,
        SentimentDetectorAuth(siteSettings.translationSvcToken)
      )
    )
  }

  /**
    * Builds a delta against the current transform context using the provided watchlist.
    * @param delta An existing delta to be used as a base.
    * @return
    */
  private def deltaWithWatchlist(langToWatchlist: Map[String, List[String]], delta: Delta = Delta()) = {
    delta.copy(
      langToWatchlist = Some(langToWatchlist)
    )
  }

  /**
    * Builds a delta against the current transform context using the provided blacklist.
    * @param delta An existing delta to be used as a base.
    * @return
    */
  private def deltaWithBlacklist(blacklist: List[BlacklistedTerm], delta: Delta = Delta()) = {
    delta.copy(
      blacklist = Some(blacklist)
    )
  }

  private class MessageHandler extends IMessageHandler {
    override def notifyException(exception: Throwable, phase: ExceptionPhase): Unit = {
      logError("Service Bus client threw error while processing message.", exception)
    }

    /**
      * Called by the QueueClient thread when a message arrives on the Service Bus.
      *
      * Note: this is configured in [[TransformContextProvider.startQueueClient]] such that it's called serially (only 1 thread
      *       will execute it at a time) which is necessary for the concurrency correctness of this module.
      */
    override def onMessageAsync(message: IMessage): CompletableFuture[Void] = {
      // Wait for the previous update of transform context to settle
      // to ensure we're calculating the delta against the latest context
      writeLock.lock()
      writeLock.unlock()

      // Read the service bus message and build delta using data store.
      val delta = Option(message.getProperties.getOrDefault("dirty", null)) match {
        case Some(value) => value match {
          case "settings" =>
            val siteSettings = configManager.fetchSiteSettings()
            deltaWithSettings(siteSettings)
          case "watchlist" =>
            val langToWatchlist = configManager.fetchWatchlist()
            deltaWithWatchlist(langToWatchlist)
          case "blacklist" =>
            val blacklist = configManager.fetchBlacklist()
            deltaWithBlacklist(blacklist)
          case unknown =>
            logError(s"Service Bus client received unexpected update request. Ignoring.: $unknown")
            Delta()
        }
        case None =>
          logError(s"Service Bus client received unexpected message. Ignoring.: ${message.toString}")
          Delta()
      }

      // Block for up to two minutes for a Spark thread to acknowledge the updated
      // state. If we time out, assume that this TransformContextProvider instance has been
      // replaced (Spark context restarted & checkpoint was discarded), and shut down to
      // allow our successor to handle the message instead.
      if (!deltaChannel.offer(delta, 2, TimeUnit.MINUTES)) {
        logDebug("Shutting down Service Bus client: timeout exceeded.")

        // Shut down client
        queueClient.closeAsync()

        throw new Exception("No Spark thread acknowledged the update message within the timeout.")
      }

      CompletableFuture.completedFuture(null)
    }
  }
}

private object TransformContextProvider {

  /**
    * Holds the next set of values for the fields of the current transform context. If the value of a field here is
    * empty, then the corresponding field of the transform context during update will be left as is.
    */
  private case class Delta(
    siteSettings: Option[SiteSettings] = None,
    langToWatchlist: Option[Map[String, List[String]]] = None,
    blacklist: Option[List[BlacklistedTerm]] = None,
    locationsExtractorFactory: Option[LocationsExtractorFactory] = None,
    imageAnalyzer: Option[ImageAnalyzer] = None,
    languageDetector: Option[LanguageDetector] = None,
    sentimentDetectorAuth: Option[SentimentDetectorAuth] = None
  )
}