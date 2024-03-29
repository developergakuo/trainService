
import TSCommon.Commons.{Response, _}
import akka.actor.ActorRef
import akka.persistence.{PersistentActor, Recovery, RecoveryCompleted, SnapshotOffer}
import scala.concurrent.duration._
import akka.util.Timeout
import akka.pattern.ask
import scala.concurrent.{Await, Future}


object TSFoodService {

  case class FoodOrderRepository(orders: Map[Int, FoodOrder])

  class FoodService(foodMapService: ActorRef, travelService: ActorRef, stationService: ActorRef)extends PersistentActor {
    var state: FoodOrderRepository = FoodOrderRepository(Map())


    override def preStart(): Unit = {
      println("TravelService prestart")
      super.preStart()
    }

    override def postRestart(reason: Throwable): Unit = {
      println("TravelService post restart")
      println(reason)
      super.postRestart(reason)
    }

    override def persistenceId = "TravelService-id"

    override def recovery: Recovery = super.recovery

    override def receiveRecover: Receive = {
      case SnapshotOffer(_, offeredSnapshot: FoodOrderRepository) ⇒ state = offeredSnapshot
      case RecoveryCompleted =>
        println("TravelService RecoveryCompleted")
      case x: Evt ⇒
        println("recovering: " + x)
        updateState(x)
    }

    def updateState(evt: Evt): Unit = evt match {
      case c: CreateFoodOrder ⇒ state = FoodOrderRepository(state.orders + (c.afoi.orderId -> c.afoi))
      case c: DeleteFoodOrder => state = FoodOrderRepository(state.orders - c.orderId)
    }

    override def receiveCommand: Receive = {
      case c:GetAllOrders =>
        println("Getting all food orders")
        sender() ! Response(0,"Success",state.orders.values.toList)
      case c:CreateFoodOrder=>
        state.orders.get(c.afoi.orderId) match {
          case Some(_) =>
            println("Error: food-order id already exists")
            sender () ! Response(1, "Error: order id already exists", None)

          case None=>
            persist(c)(updateState)
            sender() ! Response(0, "Success: added", None)
        }

      case c:DeleteFoodOrder =>
        state.orders.get(c.orderId) match {
          case Some(_) =>
            persist(c)(updateState)
            sender() ! Response(0, "Success: Deleted", None)
          case None=>
            sender () ! Response(1, "Error: order id does not exist", None)
        }

      case c:FindByOrderId =>
        state.orders.get(c.orderId) match {
          case Some(order) =>
            sender() ! Response(0, "Success: Updated", order)
          case None=>
            sender () ! Response(1, "Error: order id does not exist", None)
        }

      case c:UpdateFoodOrder =>
        state.orders.get(c.updateFoodOrder.orderId) match {
          case Some(_) =>
            persist(CreateFoodOrder(c.updateFoodOrder))(updateState)
            sender() ! Response(0, "Success: Updated", None)
          case None=>
            sender () ! Response(1, "Error: order id does not exist", None)
        }

      case FindAllFoodOrder =>
        sender() ! Response(0, "Success: Updated", state.orders.values.toList)

      case c:GetAllFood =>
        var trainFoods: Option[List[TrainFood]]= None
        val responseFuture: Future[Any] = foodMapService ? ListTrainFoodByTripId(c.tripId)
        val response = Await.result(responseFuture,duration).asInstanceOf[Response]
        if (response.status == 0) trainFoods =Some(response.data.asInstanceOf[List[TrainFood]])

        trainFoods match {
          case Some(trainFds) =>
            var route: Option[Route]= None
            val responseFuture: Future[Any] = travelService ? GetRouteByTripId(c.tripId)
            val response = Await.result(responseFuture,duration).asInstanceOf[Response]
            if (response.status == 0) route = Some(response.data.asInstanceOf[Route])

            route match {
              case Some(r) =>
                var startStationId: Option[Int] = None
                val responseFuture: Future[Any] = stationService ? QueryForIdStation(c.startStation)
                val response = Await.result(responseFuture, duration).asInstanceOf[Response]
                if (response.asInstanceOf[Response].status == 0) startStationId = Some(response.asInstanceOf[Response].data.asInstanceOf[Int])

                var endStationId: Option[Int] = None
                val response2Future: Future[Any] = stationService ? QueryForIdStation(c.endStation)
                val response2 = Await.result(response2Future, duration).asInstanceOf[Response]
                if (response2.asInstanceOf[Response].status == 0) endStationId = Some(response2.data.asInstanceOf[Int])

                val stations: List[Int] = r.stations
                val start = stations.indexOf(startStationId.get)
                val end = stations.indexOf(endStationId.get)
                val remainingStations = stations.slice(start, end)

                var foodStores: Option[List[FoodStore]] = None
                val response3Future: Future[Any] = foodMapService ? GetFoodStoresByStationIds(remainingStations)
                val response3 = Await.result(response3Future, duration).asInstanceOf[Response]
                if (response3.status == 0) foodStores = Some(response3.data.asInstanceOf[List[FoodStore]])
                sender() ! Response(0, "Success", (foodStores.get, trainFds))

              case None =>
                sender() ! Response(1, "Error: No route", None)
            }
          case None =>
            sender() ! Response(1, "Error: No train foods obtained", None)
        }

    }

  }
}
