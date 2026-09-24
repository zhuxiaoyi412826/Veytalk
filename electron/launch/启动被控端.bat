@echo off
rem 双击启动被控端：使用安装包内置 JRE（resources\jre），被控机无需安装任何 Java。
rem 工作目录固定到 %USERPROFILE%\im-remote-agent，config.properties 与日志都落在那里。
setlocal
set "BASE=%~dp0"
set "WORK=%USERPROFILE%\im-remote-agent"
if not exist "%WORK%" mkdir "%WORK%"
cd /d "%WORK%"
rem 清掉宿主机残留的 JAVA_TOOL_OPTIONS（如 trustStoreType=WINDOWS-ROOT），避免污染内置 JRE
set "JAVA_TOOL_OPTIONS="
start "" "%BASE%jre\bin\javaw.exe" -jar "%BASE%agent\im-remote-agent.jar"
