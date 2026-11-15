import re, subprocess, sys, xml.etree.ElementTree as ET

def adb(*a):
    return subprocess.run(["adb", *a], capture_output=True, text=True).stdout

def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/u.xml")
    return ET.fromstring(adb("exec-out", "cat", "/sdcard/u.xml"))

def texts(root):
    return [n.get("text") for n in root.iter("node") if n.get("text")]

def tap(label):
    for n in dump().iter("node"):
        if n.get("text") == label:
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds"))
            x = (int(m[1]) + int(m[3])) // 2
            y = (int(m[2]) + int(m[4])) // 2
            adb("shell", "input", "tap", str(x), str(y))
            return True
    return False

if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "tap":
        print("tapped" if tap(sys.argv[2]) else "missing")
    else:
        print("|".join(texts(dump())))
