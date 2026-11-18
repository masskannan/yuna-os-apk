#![no_std]
use core::panic::PanicInfo;

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
static mut LEDGER_COUNT: usize = 0;

#[no_mangle]
pub extern "C" fn record_action_event() -> usize {
    unsafe {
        LEDGER_COUNT += 1;
        LEDGER_COUNT
    }
}

#[no_mangle]
pub extern "C" fn get_ledger_count() -> usize {
    unsafe { LEDGER_COUNT }
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
