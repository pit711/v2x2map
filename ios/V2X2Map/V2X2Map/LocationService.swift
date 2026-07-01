import CoreLocation

/// CoreLocation wrapper: publishes the user's position, heading and (optionally) a
/// recorded GPS track for the map. Port of the Android own-GPS-track feature.
final class LocationService: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published var coordinate: CLLocationCoordinate2D?
    @Published var headingDeg: Double = 0
    @Published var track: [CLLocationCoordinate2D] = []

    private let mgr = CLLocationManager()
    private var recordingTrack = false

    override init() {
        super.init()
        mgr.delegate = self
        mgr.desiredAccuracy = kCLLocationAccuracyBest
        mgr.distanceFilter = 5
    }

    func start(track recording: Bool) {
        recordingTrack = recording
        mgr.requestWhenInUseAuthorization()
        // Keep GPS (and thus the track) alive while the app is backgrounded during a session.
        mgr.allowsBackgroundLocationUpdates = true
        mgr.pausesLocationUpdatesAutomatically = false
        mgr.startUpdatingLocation()
        if CLLocationManager.headingAvailable() { mgr.startUpdatingHeading() }
    }

    func setTrackRecording(_ on: Bool) {
        recordingTrack = on
        mgr.startUpdatingLocation()
    }

    func clearTrack() { track = [] }

    func locationManager(_ m: CLLocationManager, didUpdateLocations locs: [CLLocation]) {
        guard let c = locs.last?.coordinate else { return }
        coordinate = c
        guard recordingTrack else { return }
        if let last = track.last {
            let moved = CLLocation(latitude: last.latitude, longitude: last.longitude)
                .distance(from: CLLocation(latitude: c.latitude, longitude: c.longitude))
            if moved > 4 { track.append(c) }
        } else {
            track.append(c)
        }
    }

    func locationManager(_ m: CLLocationManager, didUpdateHeading h: CLHeading) {
        headingDeg = h.trueHeading >= 0 ? h.trueHeading : h.magneticHeading
    }
}
