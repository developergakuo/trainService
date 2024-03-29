import TSCommon.Commons.{Response, _}
import akka.actor.ActorRef
import akka.persistence.{PersistentActor, Recovery, RecoveryCompleted, SnapshotOffer}
import scala.concurrent.duration._
import akka.util.Timeout
import akka.pattern.ask
import scala.concurrent.{Await, Future}
import InputData.moneys

object TSInsidePaymentService {

  case class Repository(payments: Map[Int, Payment2], moneys: Map[Int,Money2])

  class InsidePaymentService(paymentService: ActorRef,
                             orderService: ActorRef, orderOtherService: ActorRef,notificationService: ActorRef) extends PersistentActor {
    var state: Repository = Repository(Map(), moneys.zipWithIndex.map(a=>a._2+1 -> a._1).toMap)


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
      case SnapshotOffer(_, offeredSnapshot: Repository) ⇒ state = offeredSnapshot
      case RecoveryCompleted =>
        println("TravelService RecoveryCompleted")
      case x: Evt ⇒
        println("recovering: " + x)
        updateState(x)
    }

    def updateState(evt: Evt): Unit = evt match {
      case c: SavePayment ⇒ state = Repository(state.payments + (c.payment.orderId -> c.payment),state.moneys)
      case c: CreateAccount =>
        state = Repository(state.payments, state.moneys + (c.info.userId -> Money2(c.info.userId,c.info.money)))
      case c:AddMoney =>
        state = Repository(state.payments, state.moneys + (c.userId -> Money2(c.userId,c.money)))
      case c:DrawBack =>
        state = Repository(state.payments, state.moneys + (c.userId -> Money2(c.userId,c.money,MoneyType().D)))

    }

    override def receiveCommand: Receive = {
      case  c:Pay =>
        var service: ActorRef = null
        if (c.info.tripId == 1 || c.info.tripId == 2) service = orderService
        else service = orderOtherService
        var order: Option[Order] = None
        val responseFuture: Future[Any] = service ? GetOrderById(c.info.orderId)
        val response = Await.result(responseFuture,duration).asInstanceOf[Response]
        if (response.status == 0) order = Some(response.data.asInstanceOf[Order])

        order match{
          case Some(o) =>
            val payment = Payment2(orderId =c.info.orderId,userId = c.info.userId, price = c.info.price)
            val totalExpand = state.payments.values.filter(payment=> payment.userId == c.info.userId)
              .map(payment => payment.price).sum + o.price
            val money = state.moneys.values.filter(money=>money.userId == c.info.userId).map(money=>money.money).sum
            if (totalExpand < money) {
              val outsidePaymentInfo =  PaymentInfo(c.info.userId,c.info.orderId,c.info.tripId,o.price)
              val responseFuture: Future[Any] = paymentService ? Pay(outsidePaymentInfo)
              val response = Await.result(responseFuture,duration).asInstanceOf[Response]
                  if (response.status == 0){
                    if(setOrderStatus(c.info.tripId, c.info.orderId)){
                      payment.paymentType = PaymentType().O
                      persist(SavePayment(payment))(updateState)
                      notificationService ! Order_Paid_success(NotifyInfo("",o.id,o.contactsName,o.to,o.from,o.travelTime,o.travelDate,o.seatClass,o.seatNumber,o.price), sender())
                    }
                    else sender() ! Response(1, "Order Change Failure", None)
                  }
                  else sender() ! Response(1, "Order Change Failure", None)
            }
            else sender() ! Response(1, "Insufficient Money Failure", None)


          case None =>
         sender() ! Response(1, "Payment Failed, Order Not Exists", null);
        }

      case  c:CreateAccount =>
        state.moneys.get(c.info.userId) match {
          case Some(_) =>
            sender() ! Response(1,"Error: acc exists", None)
          case None =>
            persist(c)(updateState)
            sender() ! Response(0,"Success: acc created", None)
        }
      case  c:AddMoney =>
        state.moneys.get(c.userId) match {
          case Some(acc) =>
            persist(AddMoney(c.userId, acc.money+c.money))(updateState)
            sender() ! Response(0,"Success: Money added", None)
          case None =>
            sender() ! Response(1,"Error: acc does not exist", None)
        }
      case  QueryPayment( ) =>
        sender() ! Response(0,"Success",state.payments.values.toList)
      case  QueryAccount( ) =>
       val balances: List[TSCommon.Commons.Balance]=state.moneys.values.map( acc => TSCommon.Commons.Balance(acc.userId,
          acc.money -
          state.payments.values.filter(p => p.userId ==acc.userId).map(acc=>acc.price).sum)).toList
       sender() ! Response(0, "Success", balances)
      case  c:DrawBack =>
        state.moneys.get(c.userId) match {
          case Some(_) =>
            persist(DrawBack(c.userId, c.money))(updateState)
            println(" =========Inside Service: DrawBackSuccess")
            sender() ! Response(0,"Success: Money drawback", None)
          case None =>
            println(" =========Inside Service: DrawBackFailure")
            sender() ! Response(1,"Error: acc does not exist", None)
        }

      case  c:PayDifference2 =>
            val payment = Payment2(orderId =c.info.orderId,userId = c.info.userId, price = c.info.price)
            val totalExpand = state.payments.values.filter(payment=> payment.userId == c.info.userId)
              .map(payment => payment.price).sum + c.info.price
            val money = state.moneys.values.filter(money=>money.userId == c.info.userId).map(money=>money.money).sum

            if (totalExpand > money) {
              val outsidePaymentInfo =  PaymentInfo(c.info.userId,c.info.orderId,c.info.tripId,c.info.price)

              val responseFuture: Future[Any] = paymentService ? Pay(outsidePaymentInfo)
                    val response = Await.result(responseFuture,duration).asInstanceOf[Response]
                  if (response.status == 0){
                    payment.paymentType = PaymentType().E
                    persist(SavePayment(payment))(updateState)
                    sender() ! Response(0, "Payment Success", None)
                  }
                  else sender() ! Response(1, "Payment Failure", None)
            }
            else {
              setOrderStatus(c.info.tripId, c.info.orderId)
              payment.paymentType = PaymentType().E
              persist(SavePayment(payment))(updateState)
            }
            sender() !  Response(0, "Payment Success", None)
          case None =>
            sender() ! Response(1, "Payment Failed, Order Not Exists", null);


      case  QueryAddMoney( ) =>
        sender() ! Response(0, "Success", state.moneys.values.toList)
    }

     def setOrderStatus(tripId: Int, orderId: Int): Boolean = {
       var service: ActorRef = null
      val orderStatus = 1
      //order paid and not collected
      var result = false
      if (tripId == 1 || tripId == 2) service = orderService
      else service = orderOtherService
       val responseFuture: Future[Any] = service ? ModifyOrder(orderId,orderStatus)
       val response = Await.result(responseFuture,duration).asInstanceOf[Response]
       if (response.status == 0) result = true
      result
    }

  }
}