package nju.service.impl;

import nju.dao.SeatRepository;
import nju.dao.TicketRecordRepository;
import nju.dao.UserInfoRepository;
import nju.entity.Seat;
import nju.entity.SitePlan;
import nju.entity.TicketRecord;
import nju.entity.UserInfo;
import nju.service.*;
import nju.util.MyDate;
import nju.util.SystemDefault;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.*;

/**
 * Created by lienming on 2018/3/10.
 */
@Service
public class TicketServiceImpl implements TicketService {

    @Autowired
    private SeatService seatService ;
    @Autowired
    private FinanceService financeService ;
    @Autowired
    private UserService userService ;
    @Autowired
    private PlanService planService ;

    @Autowired
    private TicketRecordRepository ticketRecordRepository ;
    @Autowired
    private UserInfoRepository userInfoRepository ;
    @Autowired
    private SeatRepository seatRepository ;

    /**
     * ticket transaction WITH seat position demand
     */
    public boolean buyTicket(int userID , int planID , List<String> seatList ){

        //limit amount
        if( seatList.size() > SystemDefault.SEAT_SELECTED_MAX ) {
            return false ;
        }

        boolean seatExists = seatService.checkSeatExists(planID,seatList) ;
        if(!seatExists) return false ;

        boolean lockSeatSuccess = seatService.lockSeat(userID, planID, seatList) ;

        if(!lockSeatSuccess)
            return false ;

        SitePlan sitePlan = planService.getPlanByID(planID) ;
        UserInfo userInfo = userInfoRepository.findById(userID).get() ;

        int[] ticketNum = new int[SystemDefault.SEAT_TYPE_NUM];
        for (String seatString : seatList ) {
            //judge seat type of seatNumber
            char seatType = seatString.charAt(0) ;
            int pos = seatType - 'A' ;
            ticketNum[pos]++;
        }

        /* used for single record amount calculate */
        double original_price_A = sitePlan.getOriginal_price_A() ;
        double original_price_B = sitePlan.getOriginal_price_B() ;
        double original_price_C = sitePlan.getOriginal_price_C() ;
        double[] original_price = {original_price_A,original_price_B,original_price_C} ;
        double[] discountDetail = SystemDefault.switchDiscount(userInfo.getLevel());

        double transfer_amount = financeService.transfer2plan(userID, planID, ticketNum);

        boolean transferSuccess = transfer_amount > 0 ? true : false ;

        if( transferSuccess ) {
            //create new TicketRecord
            TicketRecord tr;

            for(String seatNumber : seatList ) {
                int type = seatNumber.charAt(0) - 'A' ;
                tr = new TicketRecord(userID, sitePlan.getSiteID(), planID,
                        seatNumber, discountDetail[type]*original_price[type]) ;
                ticketRecordRepository.save(tr) ;

                // add consume record to UserInfo..
                userService.addCredit(userID, transfer_amount, tr) ;
            }

        }

        //unlocked tickets
        boolean unlockSeatSuccess =
                seatService.unlockSeat(userID,planID,seatList,transferSuccess);
        if(!unlockSeatSuccess) {
            return false ;
        }

        return transferSuccess ;
    }

    /**
     * ticket transaction WITHOUT seat position demand
     */
    public boolean buyTicket(int userID , int planID , int[] ticketNum) {

        //limit amount
        int total = 0 ;
        for(int i : ticketNum )
            total += i ;

        if( total > SystemDefault.SEAT_NOT_SELECTED_MAX ) {     //limitation
            return false ;
        }


        List<Seat> seats = seatService.lockSeat(userID, planID, ticketNum) ;
        boolean lockSeatSuccess = null==seats ? false : true ;
        if(!lockSeatSuccess) {
            return false ;  //maybe seat is not enough
        }

        SitePlan sitePlan = planService.getPlanByID(planID);
        UserInfo userInfo = userInfoRepository.findById(userID).get();

        double original_price_A = sitePlan.getOriginal_price_A() ;
        double original_price_B = sitePlan.getOriginal_price_B() ;
        double original_price_C = sitePlan.getOriginal_price_C() ;
        double[] original_price = {original_price_A,original_price_B,original_price_C} ;
        double[] discountDetail = SystemDefault.switchDiscount(userInfo.getLevel());

        double transfer_amount = financeService.transfer2plan(userID, planID, ticketNum);

        boolean transferSuccess = transfer_amount > 0 ? true : false ;

        if( transferSuccess ) {
            //create new TicketRecord
            TicketRecord tr;
            for(Seat seat : seats ) {
                String seatNumber = seat.getSeatNumber() ;
                int type = seatNumber.charAt(0) - 'A' ;
                tr = new TicketRecord(userID, sitePlan.getSiteID(), planID,
                        seatNumber, discountDetail[type] * original_price[type] ) ;
                ticketRecordRepository.save(tr) ;

                // add consume record to UserInfo..
                userService.addCredit(userID, transfer_amount, tr) ;
            }
        }

        List<String> seatNameList = new ArrayList<>() ;
        for(Seat seat : seats ) {
            seatNameList.add(seat.getSeatNumber()) ;
        }

        //unlocked tickets
        boolean unlockSeatSuccess =
                seatService.unlockSeat(userID,planID,seatNameList,transferSuccess);
        if(!unlockSeatSuccess) {
            return false ;
        }

        return transferSuccess;
    }

    /**
     * mysql
     * 配票：未选座的顾客已经在购票时配好
     *  只需要公布
     */
    public List<Seat> matchTicket(int planID,int userID) {
        return seatRepository.findByPlanIDAndUserID(planID,userID) ;
    }

    public boolean cancelOrder(TicketRecord tr) {

        Date present_time = Calendar.getInstance().getTime() ;

        SitePlan sitePlan = planService.getPlanByID(tr.getPlanID()) ;

        Timestamp perform_time = sitePlan.getBeginTime();

        int hours = MyDate.hoursBetweenDate(present_time,perform_time) ;

        if( hours <= 0 ) {
            //error
        }

        // rule ?
        double rate = SystemDefault.returnRate(hours) ;

        double return_amount = tr.getPrice() * rate ;

        boolean transferSuccess = financeService.cancelFromSite(tr,return_amount);

        boolean restoreSeatSuccess = restoreSeat(tr) ;

        return transferSuccess && restoreSeatSuccess ;
    }

    public boolean restoreSeat(TicketRecord tr) {

        int planID = tr.getPlanID() ;
        String seatNumber = tr.getSeatNumber() ;

        Seat seat = seatRepository.findByPlanIDAndSeatNumber(planID,seatNumber) ;
        seat.setState(SystemDefault.SEAT_STATE_EMPTY);
        seat.setUserID(-1);
        seatRepository.save(seat) ;
        return true ;
    }

    //only record and get seat ?
    public boolean buyTicketOffline(int planID,int userID,List<String> seats){
        for(String seatNumber : seats) {
            Seat that = seatRepository.findByPlanIDAndSeatNumber(planID,seatNumber) ;
            if( null != that) {
                that.setUserID(userID);
                that.setState(SystemDefault.SEAT_STATE_PURCHASED);
                seatRepository.save(that);
            }
        }
        return true ;
    }

    //检票
    public void checkTicket(int planID,List<String> seats){
        for(String seatNumber : seats) {
            Seat that = seatRepository.findByPlanIDAndSeatNumber(planID,seatNumber) ;
            if( null != that) {
                that.setIsChecked(true);
                seatRepository.save(that);
            }
        }
    }


}
