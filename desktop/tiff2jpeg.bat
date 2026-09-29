@echo off
rem Drag a folder of TIFF scans onto this file, or double-click and paste the path.
python "%~dp0tiff2jpeg.py" %*
pause
