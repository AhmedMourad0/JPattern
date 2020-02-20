# JPattern

## Project Overview
JPattern is a library that uses Annotation processing to eliminate the boilerplate needed to implement GOF design patterns.

## Vision

```java
@Builder
class Item {

  private [final] String name;
  private [final] String providerName;
  private [final] int amount;
  private [final] boolean isStocked;
 
  Item(String name, String providerName, int amount, boolean isStocked) {
    this.name = name;
    this.providerName = providerName;
    this.amount = amount;
    this.isStocked = isStocked;
  }
 
  //.. other constructors
 
  @Ignore
  String getName() {
    return this.name;
  }
  
  String getProviderName() {
    return this.providerName;
  }
 
  @Aliased("available")
  int getAmount() {
    return this.amount;
  }

  boolean isStocked() {
    return this.isStocked;
  }
  
  //.. setters .. hopefully not
  
  @BuilderSkeleton
  static abstract class AbstractBuilder<B extends AbstractBuilder>() {
  
    protected String amount;
    protected String providerName;
    
    public B transitions(int deposited, int withdrawn) {
      this.amount = deposited - withdrawn;
    }
    
    @Replaces("providerName")
    public B provider(Provider provider) {
      this.providerName = provider.name;
    }
    
    @OnBuild
    public void onBuild() {
      System.out.println("hi")
    }
  }
}
```


```java
Item i = new ItemBuilder()
  .provider(provider)
  .isStocked(false)
  .transition(deposited, withdrawn)
  //.available(deposited - withdrawn)
  .build(); // output: hi
```

  
