#define AppName "Tally Connector"
#define AppVersion "0.1.0"
#define AppPublisher "Ridsys"
#define AppExeName "TallyConnectorPython.exe"

[Setup]
AppId={{A03A5059-9ED3-4531-942B-B8B4A35DFAB6}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#AppPublisher}
DefaultDirName={autopf}\{#AppPublisher}\{#AppName}
DefaultGroupName={#AppName}
OutputDir=installer-dist
OutputBaseFilename=TallyConnectorPython-Setup
Compression=lzma
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin
ArchitecturesInstallIn64BitMode=x64compatible
DisableProgramGroupPage=yes

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional icons:"
Name: "autostart"; Description: "Start connector automatically when you sign in"; GroupDescription: "Windows startup:"; Flags: checkedonce

[Dirs]
Name: "{userappdata}\TallyConnectorPython"

[Files]
Source: "dist\TallyConnectorPython.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "dist\start_connector.bat"; DestDir: "{app}"; Flags: ignoreversion skipifsourcedoesntexist
Source: "dist\launch_hidden.vbs"; DestDir: "{app}"; Flags: ignoreversion skipifsourcedoesntexist
Source: "dist\.env"; DestDir: "{app}"; DestName: ".env"; Flags: ignoreversion onlyifdoesntexist skipifsourcedoesntexist
Source: "dist\.env"; DestDir: "{userappdata}\TallyConnectorPython"; DestName: ".env"; Flags: ignoreversion onlyifdoesntexist uninsneveruninstall skipifsourcedoesntexist
Source: "dist\.env.example"; DestDir: "{app}"; DestName: ".env.example"; Flags: ignoreversion skipifsourcedoesntexist
Source: "dist\.env.example"; DestDir: "{userappdata}\TallyConnectorPython"; DestName: ".env.example"; Flags: ignoreversion onlyifdoesntexist uninsneveruninstall skipifsourcedoesntexist
Source: "dist\README.md"; DestDir: "{app}"; Flags: ignoreversion skipifsourcedoesntexist

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExeName}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExeName}"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "TallyConnectorPython"; ValueData: """wscript.exe"" ""{app}\launch_hidden.vbs"" ""{app}\{#AppExeName}"""; Flags: uninsdeletevalue; Tasks: autostart

[Run]
Filename: "{app}\{#AppExeName}"; Description: "Launch Tally Connector now"; Flags: nowait postinstall skipifsilent
