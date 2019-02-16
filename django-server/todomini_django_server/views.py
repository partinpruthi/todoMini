import time
from datetime import datetime

from django.http import JsonResponse
from django.utils.timezone import make_aware

from .models import TodoFile

def server(request, folder=""):
    if request.method == "POST":
        if request.POST.get("filename", None) and request.POST.get("content", None):
            return update_file(request, folder)
        elif request.POST.get("delete", None):
            return delete_file(request, folder)
        else:
            return JsonResponse({"error": "Request for those parameters not found."}, status=404)
    else:
        return dir_poller(request, folder)

def update_file(request, folder):
    # filename: Holidays.txt
    # content: [object Object] * [ ] xyz
    filename = request.POST.get("filename", "")
    content = request.POST.get("content", "")

    if filename.endswith(".txt"):
        # return: 1549884871.1446
        f, new = TodoFile.objects.get_or_create(folder=folder, filename=filename)
        f.content = content
        f.save()
        return JsonResponse(time.time(), safe=False)
    return JsonResponse(None, safe=False)

def delete_file(request, folder):
    filename = request.POST.get("delete", "")
    # http://localhost:8000/server.php?delete=Shopping.txt
    if filename.endswith(".txt"):
        TodoFile.objects.filter(folder=folder, filename=filename).delete()
        # 1549885150.5223
        return JsonResponse(time.time(), safe=False)
    return JsonResponse(None, safe=False)

def make_timestamp(dt):
    return time.mktime(dt.timetuple()) + dt.microsecond / 1e6

def dir_poller(request, folder):
    # http://localhost:8000/server.php?timestamp=1549884871.1449&live_for=30
    # timestamp: 1549884871.1449
    # live_for: 30
    try:
        timestamp = float(request.GET.get("timestamp", 0))
    except ValueError:
        timestamp = 0
    try:
        live_for = max(0, min(int(request.GET.get("live_for", 25)), 25))
    except ValueError:
        live_for = 25
    last_modified = None
    when = make_aware(datetime.fromtimestamp(timestamp))

    while live_for:
        last_modified = TodoFile.objects.filter(folder=folder).order_by("-modified").first()
        print("dir_poller", folder, last_modified and last_modified.modified)
        if not last_modified:
            break
        elif last_modified.modified > when:
            break
        live_for -= 1
        time.sleep(1)

    # if we exited the loop early so send the files
    if last_modified and last_modified.modified > when:
        files = TodoFile.objects.filter(folder=folder).order_by("-modified")
        response = {
            "timestamp": make_timestamp(files[0].modified),
            "files": {f.filename:f.content for f in files},
            "creation_timestamps": {f.filename:make_timestamp(f.modified) for f in files},
        }
    else:
        # otherwise just send the last good timestamp
        response = {"timestamp": last_modified and make_timestamp(last_modified.modified) or (time.time() - 1)}

    # {"timestamp":1549884871.1416}

    # {"files":{"Holidays.txt":"[object Object]","Shopping.txt":"[object Object]"},"creation_timestamps":{"Holidays.txt":1549420920,"Shopping.txt":1549420853},"timestamp":1549420920.4828}

    # {"files":{"Holidays.txt":"[object Object] * [ ] goober\n * [ ] blar\n","Shopping.txt":"[object Object] * [ ] goober\n"},"creation_timestamps":{"Holidays.txt":1549884871,"Shopping.txt":1549885058},"timestamp":1549885058.6958}
    return JsonResponse(response)

