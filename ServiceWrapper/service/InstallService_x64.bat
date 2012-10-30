@echo off
REM valiables

REM log level
set DEBUG_MOD=Info
REM nitial memory pool size in MB.
set JAVA_INIT_MEMORY=10
REM Maximum memory pool size in MB.
set JAVA_MAX_MEMORY=100
REM Thread stack size in KB.
set JAVA_STACK_SIZE=100
REM path to the jvm.dll
set JVM=auto
REM Service startup mode can be either auto or manual
set START_UP=auto

if defined %CLASSPATH% set CLASSPATH=%~dp0

echo "Service Username"
set /p UserName=

echo "Service Password"
set /p PassWord=

call prunsrv64.exe //IS//V1GitIntegration --DisplayName="VersionOne Git Integration" --Description="Integration for porting information from Git to VersionOne" --Startup=%START_UP% --Jvm=%JVM% --Classpath="%~dp0..\V1GitIntegration.jar;%CLASSPATH%" ++JvmOptions="-Dderby.stream.error.file=logs/derby.log" --StartMode=jvm --StartClass=com.versionone.git.ServiceHandler --StartMethod=start --StopMode=jvm --StopClass=com.versionone.git.ServiceHandler --StopMethod=stop --StopTimeout=60 --LogPath="%~dp0logs" --StdOutput=auto --StdError=auto --LogPrefix=daemon --LogLevel=%DEBUG_MOD% --LogJniMessages=1 --JvmMs=%JAVA_INIT_MEMORY% --JvmMx=%JAVA_MAX_MEMORY% --JvmSs=%JAVA_STACK_SIZE% --ServiceUser=%UserName% --ServicePassword=%PassWord% --StartPath="%~dp0.."

call prunmgr.exe //ES//V1GitIntegration