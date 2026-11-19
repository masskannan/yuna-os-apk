#![no_std]
use core::panic::PanicInfo;
use core::sync::atomic::{AtomicUsize, Ordering};

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    loop {}
}

// Banking & Sensitive Package Shield (FLAG_SECURE Blacklist)
#[no_mangle]
pub extern "C" fn is_package_blacklisted(ptr: *const u8, len: usize) -> i32 {
    if ptr.is_null() || len == 0 { return 0; }
    let slice = unsafe { core::slice::from_raw_parts(ptr, len) };
    let blocked: [&[u8]; 6] = [
        b"com.google.android.apps.nbu.paisa.user",
        b"com.phonepe.app",
        b"net.one97.paytm",
        b"com.sbi.lotusintouch",
        b"com.icicibank.pockets",
        b"com.axis.mobile",
    ];
    for pkg in blocked.iter() {
        if slice == *pkg { return 1; }
    }
    0
}

// Ring-Buffer Action Ledger for "Bathroom Briefing"
static LEDGER_COUNT: AtomicUsize = AtomicUsize::new(0);

#[no_mangle]
pub extern "C" fn record_action_event() -> usize {
    LEDGER_COUNT.fetch_add(1, Ordering::Relaxed) + 1
}

#[no_mangle]
pub extern "C" fn get_ledger_count() -> usize {
    LEDGER_COUNT.load(Ordering::Relaxed)
}

// Offline Haversine Distance (in km) for Tamil Nadu Transport Pincodes
#[no_mangle]
pub extern "C" fn calc_road_distance_km(lat1: f64, lon1: f64, lat2: f64, lon2: f64) -> f64 {
    let r = 6371.0_f64;
    let dlat = (lat2 - lat1) * 0.017453292519943295;
    let dlon = (lon2 - lon1) * 0.017453292519943295;
    let a = (dlat * 0.5).sin().powi(2)
        + (lat1 * 0.017453292519943295).cos()
        * (lat2 * 0.017453292519943295).cos()
        * (dlon * 0.5).sin().powi(2);
    let c = 2.0 * a.sqrt().atan2((1.0 - a).sqrt());
    // 1.28x Tamil Nadu highway road curvature factor
    (r * c) * 1.28
}

// Tier 3: Strict su command allowlist (blocks arbitrary shell)
#[no_mangle]
pub extern "C" fn is_su_cmd_allowed(ptr: *const u8, len: usize) -> i32 {
    if ptr.is_null() || len == 0 { return 0; }
    let s = unsafe { core::slice::from_raw_parts(ptr, len) };
    let allowed: [&[u8]; 3] = [b"oom_score_adj", b"svc ", b"input "];
    for prefix in allowed.iter() {
        if s.len() >= prefix.len() && &s[..prefix.len()] == *prefix { return 1; }
    }
    0
}

// Tier 2: Clipboard OTP / CVV / UPI sensitivity guard
#[no_mangle]
pub extern "C" fn contains_sensitive_token(ptr: *const u8, len: usize) -> i32 {
    if ptr.is_null() || len == 0 { return 0; }
    let s = unsafe { core::slice::from_raw_parts(ptr, len) };
    let tokens: [&[u8]; 4] = [b"otp", b"cvv", b"upi", b"OTP"];
    for t in tokens.iter() {
        if s.windows(t.len()).any(|w| w == *t) { return 1; }
    }
    0
}

#[no_mangle]
pub extern "C" fn Java_com_puyal_os_nativebridge_YunaNative_nativeContainsSensitive(
    _env: *mut core::ffi::c_void, _cls: *mut core::ffi::c_void, _jstr: *mut core::ffi::c_void
) -> i32 { 0 }

#[no_mangle]
pub extern "C" fn Java_com_puyal_os_nativebridge_YunaNative_nativeIsSuAllowed(
    _env: *mut core::ffi::c_void, _cls: *mut core::ffi::c_void, _jstr: *mut core::ffi::c_void
) -> i32 { 0 }
