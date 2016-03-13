# OMG Not Another TODO App #

![Screenshot](./screenshot.png)

Simple TODO list app requirements:

 * Web based.
 * Easy to deploy.
 * Self-hosted & FLOSS.
 * Allows multiple people to update a list.
 * Mobile friendly - "Add to Home Screen" webapp.
 * Simple text based format for easy editing (see below).

Surprisingly, I could not find software meeting these criteria.

### Deploy ###

Copy the files to your PHP web hosting.

Create a password file:

	htpasswd -c /path/to/.htpasswd username

Copy `./example.htaccess` to `.htaccess` and edit it.

### Multi-user ###

If you have multiple users, clone the directory for each user and create a unique htaccess login for each.

To create a shared list, symlink one of the TODO list files into each user's data directory.

### Textfile format ###

The TODO lists use standard Markdown format:

	 * [x] This is my first completed item.
	 * [ ] Another uncompleted item.
	 * [ ] Something else.

