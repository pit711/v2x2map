import MapKit

/// MKTileOverlay that caches tiles to disk → previously-viewed areas keep working offline,
/// and sends a proper User-Agent (required by the public OSM tile servers).
final class CachingTileOverlay: MKTileOverlay {
    private let cacheDir: URL
    private let session = URLSession(configuration: .default)

    init(urlTemplate: String, cacheName: String) {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        cacheDir = base.appendingPathComponent("tiles/\(cacheName)", isDirectory: true)
        try? FileManager.default.createDirectory(at: cacheDir, withIntermediateDirectories: true)
        super.init(urlTemplate: urlTemplate)
        tileSize = CGSize(width: 256, height: 256)
        maximumZ = 19
    }

    private func cacheFile(_ path: MKTileOverlayPath) -> URL {
        cacheDir.appendingPathComponent("\(path.z)_\(path.x)_\(path.y).png")
    }

    override func loadTile(at path: MKTileOverlayPath, result: @escaping (Data?, Error?) -> Void) {
        let file = cacheFile(path)
        if let data = try? Data(contentsOf: file), !data.isEmpty {
            result(data, nil)   // served from disk → works offline
            return
        }
        var req = URLRequest(url: url(forTilePath: path))
        req.setValue("V2X2MAP-iOS/0.1 (+https://github.com/opentrafficmap)", forHTTPHeaderField: "User-Agent")
        session.dataTask(with: req) { data, _, error in
            if let data, !data.isEmpty {
                try? data.write(to: file)
                result(data, nil)
            } else {
                result(nil, error)
            }
        }.resume()
    }
}
