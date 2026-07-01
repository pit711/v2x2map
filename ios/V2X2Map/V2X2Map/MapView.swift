import SwiftUI
import MapKit

/// MKMapView wrapper: OSM/Apple tile layers (offline-cached), clustered station markers,
/// GPS-track polyline, follow + bearing-up, tap-to-select.
struct MapView: UIViewRepresentable {
    @EnvironmentObject var store: ReceiverStore
    @EnvironmentObject var settings: AppSettings
    @Binding var selected: Station?

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> MKMapView {
        let map = MKMapView()
        map.delegate = context.coordinator
        map.showsUserLocation = true
        map.showsCompass = true
        map.showsScale = true
        map.setRegion(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: 51, longitude: 10),
                                         span: MKCoordinateSpan(latitudeDelta: 12, longitudeDelta: 12)),
                      animated: false)
        context.coordinator.applyLayer(map, layer: settings.mapLayer)
        return map
    }

    func updateUIView(_ map: MKMapView, context: Context) {
        let c = context.coordinator
        c.parent = self
        c.applyLayer(map, layer: settings.mapLayer)
        c.syncAnnotations(map, stations: visibleStations)
        c.syncTrack(map, coords: store.location.track)
        c.applyFollow(map, follow: settings.follow, bearingUp: settings.bearingUp,
                      coord: store.location.coordinate, heading: store.location.headingDeg)
    }

    private var visibleStations: [Station] {
        store.stations.filter { $0.hasPosition && settings.visibleTypes.contains($0.msgType) }
    }

    final class Coordinator: NSObject, MKMapViewDelegate {
        var parent: MapView
        private var layer: AppSettings.MapLayer?
        private var tile: MKTileOverlay?
        private var track: MKPolyline?
        private var anns: [UInt64: StationAnnotation] = [:]
        private var lastFollow: CLLocationCoordinate2D?

        init(_ parent: MapView) { self.parent = parent }

        func applyLayer(_ map: MKMapView, layer newLayer: AppSettings.MapLayer) {
            guard newLayer != layer else { return }
            layer = newLayer
            if let t = tile { map.removeOverlay(t); tile = nil }
            if let template = newLayer.urlTemplate {
                let o = CachingTileOverlay(urlTemplate: template, cacheName: newLayer.rawValue)
                o.canReplaceMapContent = true
                map.insertOverlay(o, at: 0, level: .aboveLabels)
                tile = o
            } else {
                map.preferredConfiguration = (newLayer == .appleHybrid)
                    ? MKHybridMapConfiguration() : MKStandardMapConfiguration()
            }
        }

        func syncAnnotations(_ map: MKMapView, stations: [Station]) {
            var seen = Set<UInt64>()
            for s in stations {
                seen.insert(s.id)
                if let a = anns[s.id] { a.apply(s) }
                else { let a = StationAnnotation(s); anns[s.id] = a; map.addAnnotation(a) }
            }
            for (id, a) in anns where !seen.contains(id) {
                map.removeAnnotation(a); anns.removeValue(forKey: id)
            }
        }

        func syncTrack(_ map: MKMapView, coords: [CLLocationCoordinate2D]) {
            if let t = track { map.removeOverlay(t); track = nil }
            guard coords.count > 1 else { return }
            let line = MKPolyline(coordinates: coords, count: coords.count)
            map.addOverlay(line, level: .aboveLabels)
            track = line
        }

        func applyFollow(_ map: MKMapView, follow: Bool, bearingUp: Bool,
                         coord: CLLocationCoordinate2D?, heading: Double) {
            guard follow, let coord else { return }
            if let last = lastFollow, !bearingUp {
                let moved = CLLocation(latitude: last.latitude, longitude: last.longitude)
                    .distance(from: CLLocation(latitude: coord.latitude, longitude: coord.longitude))
                if moved < 3 { return }
            }
            lastFollow = coord
            let cam = MKMapCamera(lookingAtCenter: coord, fromDistance: 1500, pitch: 0,
                                  heading: bearingUp ? heading : 0)
            map.setCamera(cam, animated: true)
        }

        // MARK: delegate

        func mapView(_ map: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            if let t = overlay as? MKTileOverlay { return MKTileOverlayRenderer(tileOverlay: t) }
            if let l = overlay as? MKPolyline {
                let r = MKPolylineRenderer(polyline: l)
                r.strokeColor = .systemBlue
                r.lineWidth = 4
                return r
            }
            return MKOverlayRenderer(overlay: overlay)
        }

        func mapView(_ map: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
            if annotation is MKUserLocation { return nil }
            if annotation is MKClusterAnnotation {
                let v = MKMarkerAnnotationView(annotation: annotation, reuseIdentifier: "cluster")
                v.markerTintColor = .darkGray
                v.titleVisibility = .hidden
                return v
            }
            guard let s = annotation as? StationAnnotation else { return nil }
            let v = (map.dequeueReusableAnnotationView(withIdentifier: "station") as? MKMarkerAnnotationView)
                ?? MKMarkerAnnotationView(annotation: annotation, reuseIdentifier: "station")
            v.annotation = annotation
            v.clusteringIdentifier = "station"
            v.markerTintColor = UIColor(argb: s.station.msgType.colorARGB)
            v.glyphText = s.station.msgType.short
            v.titleVisibility = .adaptive
            v.displayPriority = .defaultHigh
            return v
        }

        func mapView(_ map: MKMapView, didSelect view: MKAnnotationView) {
            if let s = view.annotation as? StationAnnotation {
                parent.selected = s.station
                map.deselectAnnotation(view.annotation, animated: false)
            } else if let cluster = view.annotation as? MKClusterAnnotation {
                var rect = MKMapRect.null
                for a in cluster.memberAnnotations {
                    let p = MKMapPoint(a.coordinate)
                    rect = rect.union(MKMapRect(x: p.x, y: p.y, width: 0, height: 0))
                }
                map.setVisibleMapRect(rect, edgePadding: UIEdgeInsets(top: 80, left: 80, bottom: 80, right: 80),
                                      animated: true)
            }
        }
    }
}

/// MKAnnotation backing one station marker. `coordinate` is KVO-observable so MapKit
/// animates marker moves in place.
final class StationAnnotation: NSObject, MKAnnotation {
    @objc dynamic var coordinate: CLLocationCoordinate2D
    private(set) var station: Station

    init(_ s: Station) {
        station = s
        coordinate = s.coordinate
        super.init()
    }

    var title: String? { station.macLabel }
    var subtitle: String? { station.typeName }

    func apply(_ s: Station) {
        station = s
        coordinate = s.coordinate
    }
}
