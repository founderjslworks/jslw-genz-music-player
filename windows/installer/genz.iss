; GEN-Z Music for Windows – installer (Inno Setup 6)
; Installs for the current user (no admin needed) or for everyone, adds Start menu / desktop shortcuts
; and an uninstaller in "Apps & features". Windows 7 SP1 – Windows 11.

#ifndef AppVersion
  #define AppVersion "1.2.0"
#endif
#define AppName "GEN-Z Music"
#define AppExe "GEN-Z Music.exe"
#define Publisher "JSL WORKS PVT LTD"
#define Url "https://www.jslworks.in/android-devlopment"

[Setup]
AppId={{6F2C5B1E-8C1A-4E0B-9B57-6A1D2E4C9F31}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher={#Publisher}
AppPublisherURL=https://www.jslworks.in/
AppSupportURL={#Url}
AppUpdatesURL={#Url}
AppCopyright=© 2026 JSL WORKS PVT LTD. Made in India with love.
VersionInfoVersion={#AppVersion}
VersionInfoCompany={#Publisher}
VersionInfoDescription={#AppName} Setup
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
OutputDir=..\..\dist
OutputBaseFilename=GEN-Z-Music-Setup-{#AppVersion}
SetupIconFile=..\GenZ.Desktop\Assets\genz.ico
UninstallDisplayIcon={app}\{#AppExe}
UninstallDisplayName={#AppName}
WizardStyle=modern
WizardImageFile=wizard.bmp,wizard_2x.bmp
WizardSmallImageFile=small.bmp,small_2x.bmp
LicenseFile=..\..\LICENSE
Compression=lzma2/max
SolidCompression=yes
MinVersion=6.1sp1
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a &desktop shortcut"; GroupDescription: "Shortcuts:"
Name: "openwith"; Description: "Show GEN-Z Music in ""Open with"" for music files"; GroupDescription: "Integration:"

[Files]
Source: "..\GenZ.Desktop\bin\Release\net48\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs; Excludes: "*.pdb"

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"; Comment: "GEN-Z Music – offline music player"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Registry]
; "Open with" entry for common audio files
Root: HKA; Subkey: "Software\Classes\Applications\{#AppExe}"; ValueType: string; ValueName: "FriendlyAppName"; ValueData: "{#AppName}"; Flags: uninsdeletekey; Tasks: openwith
Root: HKA; Subkey: "Software\Classes\Applications\{#AppExe}\shell\open\command"; ValueType: string; ValueName: ""; ValueData: """{app}\{#AppExe}"" ""%1"""; Tasks: openwith
Root: HKA; Subkey: "Software\Classes\Applications\{#AppExe}\SupportedTypes"; ValueType: string; ValueName: ".mp3"; ValueData: ""; Tasks: openwith
Root: HKA; Subkey: "Software\Classes\Applications\{#AppExe}\SupportedTypes"; ValueType: string; ValueName: ".flac"; ValueData: ""; Tasks: openwith
Root: HKA; Subkey: "Software\Classes\Applications\{#AppExe}\SupportedTypes"; ValueType: string; ValueName: ".wav"; ValueData: ""; Tasks: openwith
Root: HKA; Subkey: "Software\Classes\Applications\{#AppExe}\SupportedTypes"; ValueType: string; ValueName: ".m4a"; ValueData: ""; Tasks: openwith
Root: HKA; Subkey: "Software\Classes\Applications\{#AppExe}\SupportedTypes"; ValueType: string; ValueName: ".aiff"; ValueData: ""; Tasks: openwith
Root: HKA; Subkey: "Software\Classes\.mp3\OpenWithList\{#AppExe}"; ValueType: none; Flags: uninsdeletekey; Tasks: openwith
Root: HKA; Subkey: "Software\Classes\.flac\OpenWithList\{#AppExe}"; ValueType: none; Flags: uninsdeletekey; Tasks: openwith
Root: HKA; Subkey: "Software\Classes\.wav\OpenWithList\{#AppExe}"; ValueType: none; Flags: uninsdeletekey; Tasks: openwith
Root: HKA; Subkey: "Software\Classes\.m4a\OpenWithList\{#AppExe}"; ValueType: none; Flags: uninsdeletekey; Tasks: openwith

[Run]
Filename: "{app}\{#AppExe}"; Description: "Start {#AppName} now"; Flags: nowait postinstall skipifsilent

[Code]
// .NET Framework 4.8 is built into Windows 10 / 11; Windows 7 and 8.1 may need it.
function NetFx48Installed(): Boolean;
var
  release: Cardinal;
begin
  Result := RegQueryDWordValue(HKLM, 'SOFTWARE\Microsoft\NET Framework Setup\NDP\v4\Full', 'Release', release) and (release >= 528040);
end;

function InitializeSetup(): Boolean;
var
  code: Integer;
begin
  Result := True;
  if not NetFx48Installed() then
  begin
    if MsgBox('GEN-Z Music needs Microsoft .NET Framework 4.8 (a free update from Microsoft).' + #13#10#13#10 +
              'Open the download page now? Install it, then run this setup again.', mbConfirmation, MB_YESNO) = IDYES then
      ShellExec('open', 'https://dotnet.microsoft.com/download/dotnet-framework/net48', '', '', SW_SHOWNORMAL, ewNoWait, code);
    Result := False;
  end;
end;
