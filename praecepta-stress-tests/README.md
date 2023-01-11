# How to run stress-tests

To run all the simulations, you just need to execute the following line:

```
$ mvn gatling:test
```

The output reports will be inside `./target/gatling/`.

In other to define environment configuration, you can use the following variables.

| Variable                 | Description                                                                                                                    |                                             Default value |
|--------------------------|--------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------:|
| user                     | number of concurrent users.                                                                                                    |                                                       100 |
| durationInSecs           | How long should the test last.                                                                                                 |                                                        60 |
| baseUrl                  | The service url.                                                                                                               | http://localhost:8080 |

Below you can find an example on how to add variables:

```
$ mvn gatling:test \
    -Dusers=100 \
    -DdurationInSecs=60 \
    -DbaseUrl="http://localhost:8080"
```