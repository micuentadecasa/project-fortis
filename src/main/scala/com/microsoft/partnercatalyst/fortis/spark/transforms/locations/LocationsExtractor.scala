package com.microsoft.partnercatalyst.fortis.spark.transforms.locations

import com.microsoft.partnercatalyst.fortis.spark.transforms.locations.client.FeatureServiceClient
import com.microsoft.partnercatalyst.fortis.spark.transforms.Location

import scala.collection.mutable

case class Geofence(north: Double, west: Double, south: Double, east: Double)

@SerialVersionUID(100L)
class LocationsExtractor(
  featureServiceClient: FeatureServiceClient,
  geofence: Geofence,
  placeRecognizer: Option[PlaceRecognizer] = None,
  ngrams: Int = 3
) extends Serializable {

  protected var lookup: Map[String, Set[String]] = _

  def buildLookup(): this.type = {
    val map = mutable.Map[String, mutable.Set[String]]()
    featureServiceClient.bbox(geofence).foreach(location => {
      val key = location.name.toLowerCase
      val value = location.id
      map.getOrElseUpdate(key, mutable.Set()).add(value)
    })

    lookup = map.map(kv => (kv._1, kv._2.toSet)).toMap
    this
  }

  def analyze(text: String, language: Option[String] = None): Iterable[Location] = {
    val candidatePlaces = extractCandidatePlaces(text, language)
    val locationsInGeofence = candidatePlaces.flatMap(place => lookup.get(place.toLowerCase)).flatten.toSet
    locationsInGeofence.map(wofId => Location(wofId, confidence = Some(0.5)))
  }

  private def extractCandidatePlaces(text: String, language: Option[String]): Iterable[String] = {
    var candidatePlaces = Iterable[String]()
    if (placeRecognizer.isDefined) {
      candidatePlaces = placeRecognizer.get.extractPlaces(text, language.getOrElse(""))
    }
    if (candidatePlaces.isEmpty) {
      candidatePlaces = StringUtils.ngrams(text, ngrams)
    }
    candidatePlaces
  }

  def fetch(latitude: Double, longitude: Double): Iterable[Location] = {
    val locationsForPoint = featureServiceClient.point(latitude = latitude, longitude = longitude)
    val locationsInGeofence = locationsForPoint.flatMap(location => lookup.get(location.name.toLowerCase)).flatten.toSet
    locationsInGeofence.map(wofId => Location(wofId, confidence = Some(1.0)))
  }
}
