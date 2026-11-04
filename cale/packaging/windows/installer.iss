; SPDX-License-Identifier: GPL-3.0-only
#define AppName "PassThru Cale"
[Setup]
AppId=PassThru-Cale
AppName={#AppName}
AppVersion=2.0
DefaultDirName={localappdata}\Programs\PassThru Cale
DefaultGroupName={#AppName}
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=output
OutputBaseFilename=PassThru-Cale-Setup
LicenseFile=..\..\..\LICENSE
Compression=lzma2
SolidCompression=yes
WizardStyle=modern

[Tasks]
Name: "privatefirewall"; Description: "Allow Cale on Private networks only (recommended; asks for administrator approval once)"

[Files]
Source: "dist\PassThru Cale\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\PassThru Cale"; Filename: "{app}\PassThru Cale.exe"

[Run]
; Only netsh elevates. No Public-profile rule is ever created.
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""PassThru Cale (Private)"" dir=in action=allow program=""{app}\PassThru Cale.exe"" enable=yes profile=private"; Verb: "runas"; Flags: shellexec waituntilterminated; Tasks: privatefirewall
