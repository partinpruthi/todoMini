NAME=$(shell basename `pwd`)
RESOURCES_NAMES=font-awesome/css font-awesome/fonts manifest.json sortable/Sortable.min.js img
SERVER_FILES=server.php example.htaccess LICENSE.txt data/

RESOURCES_SRC=$(addprefix resources/public/, $(RESOURCES_NAMES))
RESOURCES=$(addprefix build/, $(RESOURCES_NAMES))
IDX=build/index.html
APP=build/js/app.js
CSS=build/css/site.min.css
SERVER=$(addprefix build/, $(SERVER_FILES))

TARGETS=$(RESOURCES) $(IDX) $(APP) $(CSS) $(SERVER)

all: $(TARGETS)

$(RESOURCES): $(RESOURCES_SRC)
	@echo "Copying resources:" $@
	@mkdir -p `dirname $@`
	@cp -avr $(subst build, resources/public, $@) $@
	@touch $@

$(CSS): resources/public/css/site.css
	lein minify-assets
	
$(APP): src/**/** project.clj
	rm -f $(APP)
	lein cljsbuild once min

$(SERVER): $(SERVER_FILES)
	cp -avr $(subst build/,,$@) $@
	@touch $@

$(IDX): src/clj/*/*.clj
	lein run -m omgnata.handler/index-html > $(IDX)

clean:
	rm -rf $(TARGETS) build
