// Test-only build metadata seam. Upstream generates this non-algorithm module
// at release-build time; it is absent from the source archive. No oracle calls
// its logger/exporter. Fail rather than fabricate values if that ever changes.
export const VersionInfo:any = new Proxy({}, {
    get:(_target,key)=>{throw new Error(`Oracle unexpectedly used build metadata: ${String(key)}`);}
});
