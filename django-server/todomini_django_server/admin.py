from django.contrib import admin

from .models import TodoFile

class TodoFileAdmin(admin.ModelAdmin):
    list_display = ("__str__", "created", "modified", "days_used")

    def days_used(self, instance):
        if instance:
            return str(instance.modified - instance.created)

admin.site.register(TodoFile, TodoFileAdmin)
