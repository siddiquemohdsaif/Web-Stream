@echo off
set SERVER_USERNAME=root
set SERVER_IP=168.144.23.108
set SERVER_PASSWORD=cxroyal2@Lion
set TARGET_DIR=/node/WebStreamNodeServer
set TEMP_DIR=/tmp/
set POST_COMMAND= pm2 restart webStreamServer    && pm2 ls

REM Step 1: Compress current directory contents into code.rar : install sudo apt update , sudo apt install unrar
"C:\Program Files\WinRAR\Rar.exe" a -r -inul code.rar * -x*.git\

REM Step 2: Upload code.rar to the temporary directory on the server using PowerShell
powershell -command "$process = Start-Process 'scp' -ArgumentList 'code.rar %SERVER_USERNAME%@%SERVER_IP%:%TEMP_DIR%' -NoNewWindow -PassThru; Start-Sleep -Seconds 1; $wshell = New-Object -ComObject wscript.shell; $wshell.SendKeys('%SERVER_PASSWORD%{ENTER}'); $process.WaitForExit()"

REM Step 2.5: Delete the code.rar file from the local machine after upload
del code.rar

REM Step 3: SSH into the server, execute the commands, and then start an interactive shell session
set SSH_COMMANDS=rm -r %TARGET_DIR% ^&^& mkdir %TARGET_DIR% ^&^& mv %TEMP_DIR%code.rar %TARGET_DIR% ^&^& cd %TARGET_DIR% ^&^& unrar x code.rar ^&^& rm code.rar ^&^& %POST_COMMAND% ^&^& bash
start cmd /k ssh -t %SERVER_USERNAME%@%SERVER_IP% "echo %SERVER_PASSWORD% | sudo -S %SSH_COMMANDS%"
timeout /t 2
powershell -command "$wshell = New-Object -ComObject wscript.shell; $wshell.AppActivate('cmd.exe'); $wshell.SendKeys('%SERVER_PASSWORD%{ENTER}')"