# Learnings

Short notes on what I learned doing each task — concepts, gotchas,
patterns. 

## Phase 0
- Docker:
client - host (daemon) - registry

dockerfile - set of instructions/blueprint for image creation and setting up
image - the built immutable artifact: filesystem layers + libraries + packages + metadata
container - running instance of an image (one image → many containers), isolated process(es) running on the host kernel (NOT a VM, NOT its own OS), made from an image, managed by daemon
Client = sends commands like docker run <IMAGE_ID>, then daemon inside host works on container and image_id
Host = the machine running docker; containers share its kernel (that's why they're lighter than VMs)
commands: docker images, docker container ls -a, docker run -w(for dir) -d(background), docker container create (by image id), docker build(make an image by docker file), docker compose ...

sourcepath = build-time, where .java source files live, only compiler works with this, no runtime caring.
classpath = runtime, where JVM looks for compiled .class files and resources at runtime. its a list of dirs and jar files for the jvm.
jar: everything from classpath, flattened.
Configs should always reference classpath locs, as sourcepath dont exist in packaged jar.

Spring Boot autoconfiguration: starters bring in deps, wire up based on classpath or whats already defined
@SpringBootApplication = @Configuration + @EnableAutoConfiguration + @ComponentScan, so it can specify beans(especially bean configs) and scan classpath