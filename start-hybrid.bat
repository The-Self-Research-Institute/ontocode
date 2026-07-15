@echo off
REM Mongo + Fuseki in Docker; Auth/Gateway/Editor/SWRL/Plugin via Maven.
REM UI hot reload: pass -WithFrontend
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-hybrid.ps1" %*
