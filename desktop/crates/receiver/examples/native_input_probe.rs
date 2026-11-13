//! Read-only native preflight probe. Never executes input or opens production data.
use uuid::Uuid;
use voicelink_receiver::{
    protocol::{Operation, Submit, TextSource},
    windows_input::{NativeInput, open_ledger},
    worker::InputAdapter,
};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    use windows::Win32::{
        Foundation::{CloseHandle, HANDLE},
        Security::{GetTokenInformation, TOKEN_ELEVATION, TOKEN_QUERY, TokenElevation},
        System::Threading::{GetCurrentProcess, OpenProcessToken},
    };
    unsafe {
        let mut token = HANDLE::default();
        OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token)?;
        let mut elevation = TOKEN_ELEVATION::default();
        let mut size = 0;
        let result = GetTokenInformation(
            token,
            TokenElevation,
            Some((&mut elevation as *mut TOKEN_ELEVATION).cast()),
            std::mem::size_of::<TOKEN_ELEVATION>() as u32,
            &mut size,
        );
        let _ = CloseHandle(token);
        result?;
        println!(
            "receiver_process_elevated={}",
            elevation.TokenIsElevated != 0
        );
    }
    let directory = std::env::temp_dir().join(format!("voicelink-preflight-{}", Uuid::new_v4()));
    std::fs::create_dir(&directory)?;
    let mut ledger = open_ledger(&directory)?;
    let device = Uuid::new_v4().to_string();
    ledger.provision_device(&device, &[77; 32], "Isolated native probe".into())?;
    let session = ledger.authenticate(&device, &[77; 32])?;
    let target = Uuid::new_v4().to_string();
    let state = ledger.bind_target(&target)?;
    ledger.resume(&state.state_rev, false)?;
    ledger.submit(
        &session,
        &Submit::new(
            session.id().into(),
            Uuid::new_v4().to_string(),
            target,
            Operation::Text {
                text: "probe never injects".into(),
                src: TextSource::Manual,
            },
        ),
    )?;
    let job = ledger.prepare_next()?.ok_or("No probe job")?;
    let mut native = NativeInput::default();
    println!("preflight={:?}", native.preflight(&job));
    if let Some(app) = native.prepared_application() {
        println!("target_name={}", app.name());
    }
    drop(job);
    drop(ledger);
    std::fs::remove_dir_all(&directory)?;
    Ok(())
}
