import UIKit
import ImageIO

public protocol MessageImageLoader {
    func load(_ url: URL) async throws -> UIImage
}

/// Bounded downloads and downsampling; hosts can inject their authenticated transport or image cache.
public final class URLSessionMessageImageLoader: MessageImageLoader {
    public static let shared = URLSessionMessageImageLoader()
    private let cache = NSCache<NSURL, UIImage>()
    private let baseURL: URL?
    public init(baseURL: URL? = nil) { self.baseURL = baseURL; cache.countLimit = 128; cache.totalCostLimit = 32 * 1024 * 1024 }
    public func load(_ reference: URL) async throws -> UIImage {
        let url = reference.scheme == nil ? URL(string: reference.relativeString, relativeTo: baseURL)?.absoluteURL ?? reference : reference
        guard ["https", "http"].contains(url.scheme?.lowercased() ?? "") else { throw URLError(.unsupportedURL) }
        try Task.checkCancellation()
        if let image = cache.object(forKey: url as NSURL) { return image }
        let request = BoundedImageRequest(url)
        let data: Data = try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { request.start($0) }
        }, onCancel: { request.cancel() })
        try Task.checkCancellation()
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let bitmap = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceThumbnailMaxPixelSize: 1024,
                kCGImageSourceCreateThumbnailWithTransform: true,
              ] as CFDictionary) else { throw URLError(.cannotDecodeContentData) }
        let image = UIImage(cgImage: bitmap)
        cache.setObject(image, forKey: url as NSURL, cost: bitmap.bytesPerRow * bitmap.height)
        return image
    }
}

private final class BoundedImageRequest: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let url: URL
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Data, Error>?
    private var session: URLSession?
    private var task: URLSessionDataTask?
    private var cancelled = false
    private var bytes = Data()
    private var failure: Error?
    private let limit = 16 * 1024 * 1024
    init(_ url: URL) { self.url = url }
    func start(_ continuation: CheckedContinuation<Data, Error>) {
        lock.lock()
        if cancelled { lock.unlock(); continuation.resume(throwing: CancellationError()); return }
        self.continuation = continuation
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpShouldSetCookies = false
        configuration.timeoutIntervalForRequest = 30
        configuration.timeoutIntervalForResource = 30
        let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
        self.session = session
        let task = session.dataTask(with: url)
        self.task = task
        lock.unlock()
        task.resume()
    }
    func cancel() { lock.lock(); cancelled = true; let task = self.task; lock.unlock(); task?.cancel() }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
              response.expectedContentLength <= Int64(limit) else {
            failure = URLError(.badServerResponse); completionHandler(.cancel); return
        }
        completionHandler(.allow)
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        if bytes.count + data.count > limit { failure = URLError(.dataLengthExceedsMaximum); dataTask.cancel(); return }
        bytes.append(data)
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock(); let continuation = self.continuation; self.continuation = nil; self.task = nil; self.session = nil; lock.unlock()
        if let error = failure ?? error { continuation?.resume(throwing: error) }
        else { continuation?.resume(returning: bytes) }
        session.finishTasksAndInvalidate()
    }
}

/// Work starts on attachment and belongs to this view; detachment and destruction cancel it.
final class LoadingMessageImageView: UIImageView {
    private let url: URL?
    private let loader: MessageImageLoader
    private var loading: Task<Void, Never>?
    init(reference: MessageImageReference, loader: MessageImageLoader) {
        self.url = reference.url.isEmpty ? nil : URL(string: reference.url)
        self.loader = loader
        super.init(frame: .zero)
        accessibilityLabel = reference.alternateText
        isAccessibilityElement = true
    }
    @available(*, unavailable) required init?(coder: NSCoder) { fatalError() }
    override func didMoveToWindow() {
        super.didMoveToWindow()
        loading?.cancel(); loading = nil
        guard window != nil, let url = url else { image = nil; return }
        let loader = self.loader
        loading = Task { [weak self] in
            guard let loaded = try? await loader.load(url), !Task.isCancelled else { return }
            self?.image = loaded
        }
    }
    deinit { loading?.cancel() }
}
