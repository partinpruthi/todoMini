from django.db import models

class TodoFile(models.Model):
    created = models.DateTimeField(auto_now_add=True)
    modified = models.DateTimeField(auto_now=True)
    folder = models.CharField(max_length=256)
    filename = models.CharField(max_length=256)
    content = models.TextField()

    def __str__(self):
        return self.folder + "/" + self.filename
